# Barn edge gateway

Store-and-forward gateway that sits in the barn. Reader events go into a durable local queue first and are
forwarded to the VinaBeef backend from there, so a lost uplink or a down backend does not lose events.

```
Safetrust MQTT (or a LAN broker) ──┐
                                    ├─► SQLite queue (WAL, synchronous=FULL) ─► forwarder ─► POST /api/v1/ingestion/batch
HTTP POST :8090 (LAN, test tools) ──┘                     ▲ deleted only after the backend confirms each event
```

## Run

```bash
pip install -r requirements.txt
cp edge.env.example .env        # fill in MQTT_PASSWORD, then:  set -a; . ./.env; set +a
python barn_edge_gateway.py
curl http://localhost:8090/status
```

Docker: `docker build -t barn-edge . && docker run -d --restart=always --env-file .env -v edge-data:/data -p 8090:8090 barn-edge`

## What it guarantees

* An event is on disk (`synchronous=FULL`) before the gateway returns from the MQTT callback or answers the HTTP POST.
* An event is deleted only when the backend answers 200, or lists it as `OK` in a 207 reply. A failed event is kept and retried
  with backoff; after `EDGE_MAX_ATTEMPTS` it is marked `DEAD` (kept, visible in `/status`) so it cannot block the others.
* Events are sent oldest hardware time first. The backend is idempotent on `EVENT_KEY`, so a resend is harmless.
* The backend health probe checks its database, so the backlog is not replayed into a backend that cannot write.
* The MQTT client subscribes again after every reconnect, and keeps retrying if the first connection fails.

## What it does not do

It protects the path **gateway → backend**. If the *readers* cannot reach the Safetrust cloud (the barn has no Internet), Safetrust
publishes nothing and this gateway has nothing to queue. Closing that gap needs readers that buffer, or publish to a LAN broker,
which has to be confirmed with Safetrust. See the project notes.

## Endpoints

| | |
|---|---|
| `POST /` | one event object or a list; `202 {"queued":n,"duplicates":m}`. Needs `X-Edge-Token` when `EDGE_INGEST_TOKEN` is set |
| `GET /status` | link state (`ONLINE`/`DEGRADED`/`OFFLINE`), MQTT state, queue depth, oldest pending age, counters |
| `GET /health` | liveness |

## Testing a lost link

`python drill/wan_drill.py` runs the gateway against a local broker and a fake backend and cuts every link in turn (needs `pip install amqtt paho-mqtt httpx`).
`WAN-LOSS-TEST.md` is the procedure for the same tests on the real barn: which of the three links is lost, how to cut it, what to check afterwards.

## Tests

`python -m pytest -q` (unit tests; no broker or backend needed).
