# Barn edge gateway

Store-and-forward gateway that sits in the barn. Reader events go into a durable local queue first and are
forwarded to the VinaBeef backend from there, so a lost uplink or a down backend does not lose events.

```
Safetrust MQTT (or a LAN broker) ──┐
HTTP POST :8090 (LAN, test tools) ──┼─► SQLite queue (WAL, synchronous=FULL) ─► forwarder ─► POST /api/v1/ingestion/batch
USB readers (keyboard / serial) ────┘     ▲ deleted only after the backend confirms each event
 POST :8090/local-swipe                   └ USB swipes wait here briefly for the reader's own copy (see "USB readers")
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

It protects the path **gateway → backend**. The Safetrust readers talk only to the Safetrust cloud, so when the *readers* cannot reach
it (the barn has no Internet) Safetrust publishes nothing and the MQTT side of this gateway has nothing to queue. Two things close that
gap, and which one you need depends on a fact to be measured: **USB readers** next to the wall readers (below), and/or readers that
buffer offline (`WAN-LOSS-TEST.md`, Test 5). The cloud copes with both at once.

## USB readers (Safetrust USB IoT)

A USB reader stands next to a wall reader and reports the same tap to **this gateway** over a wire, so it still works when the barn has
no Internet. It sends only a credential number (`38`, `1005`): no time, no card UUID. The gateway:

1. **stamps the time** with its own clock (`EDGE_TIME_SOURCE=GATEWAY_CLOCK`; keep the gateway's clock right, NTP) and gives the swipe a
   **negative `EVENT_KEY`** = `-(EDGE_GATEWAY_NO × 10^13 + counter)`. Safetrust keys are positive, so the two can never collide. The counter is
   persistent and never below the clock, so even a wiped database cannot reuse a key.
2. **stores it durably** at once (same file, same transaction style as every other event).
3. **holds it** for `LOCAL_HOLD_MS` (6 s) *only while MQTT is connected*, watching for the reader's own copy of the tap on the MQTT feed.
   Copy arrives → the swipe is a **shadow** (kept in the swipe log, not sent). None arrives → it is **queued** and forwarded.
   With MQTT down no copy can reach this gateway, so there is nothing to wait for and the swipe is queued at once.
4. forwards it. **The cloud finds the card**: it looks up what card Safetrust itself reported with that credential number, then the tag register.
   A number it cannot place is kept as an *unmapped USB read* with an alert; it is processed as soon as the number becomes known (`POST /api/v1/ingestion/local/resolve`).

The link state is only a shortcut for step 3. It does **not** prove that no copy exists (the reader's own uplink can be down while the gateway's is up),
and the edge only sees copies that reach *this* gateway by MQTT. Everything else is decided in the cloud by the cross-route matcher
(`ingestion.cross-source.*`): same reader, same card, different route, reader TIMESTAMPs within the window → one movement, the second read kept as evidence.

Setup is `LOCAL_INPUTS` in `edge.env.example`. `kbd:` reads `/dev/input/eventX` directly and **grabs it exclusively** so the digits do not also land in a console
(the container needs `--device=/dev/input/eventX --group-add input`). `serial:` needs `pyserial` (`--device=/dev/ttyACM0 --group-add dialout`). A reader that sends no
Enter is handled: a swipe also ends after `LOCAL_IDLE_FLUSH_MS` of silence. Text that is not 1 to 12 digits is **rejected and counted** (`counters.localRejected`),
never guessed: a key the decoder does not know makes the swipe invalid instead of turning it into a different number.

**Direction needs two USB readers**, one next to the inner and one next to the outer wall reader (each mapped with `=<reader name>`). One USB reader gives presence only.
**OSDP is not implemented.** The evdev and serial code has been tested with simulated devices, not with the real hardware.

`GET /status` → `local`: `held`, `queued`, `shadow`, the inputs and whether each is connected, and `clockOffset`: the gateway's arrival time minus the reader's own `TIMESTAMP` over the last 50 reads
(median and largest). On a healthy link that is the reader clock error plus network delay: **use it to size the matching window** (`LOCAL_MATCH_SKEW_MS` here and `INGESTION_CROSS_SOURCE_WINDOW_MS` in the cloud).

## Endpoints

| | |
|---|---|
| `POST /` | one event object or a list; `202 {"queued":n,"duplicates":m}`. Needs `X-Edge-Token` when `EDGE_INGEST_TOKEN` is set |
| `POST /local-swipe` | `{"reader":"DrC0F8","credential":"38","device":"kiosk-1"}` (device optional). The gateway stamps the time; the caller cannot. `202 {"state":"HELD\|QUEUED\|SHADOW","eventKey":-1…}`, `400` for a bad number or a reader this gateway does not serve. Same `X-Edge-Token` rule |
| `GET /status` | link state (`ONLINE`/`DEGRADED`/`OFFLINE`), MQTT state, queue depth (`held` = USB swipes waiting), oldest pending age, counters, `local` (USB readers) |
| `GET /health` | liveness |

## Testing a lost link

`python drill/wan_drill.py` runs the gateway against a local broker and a fake backend and cuts every link in turn (needs `pip install amqtt paho-mqtt httpx`).
`python drill/usb_drill.py` does the same for the USB path: a real gateway process, a stdin "USB reader", copy / no copy / broker lost / backend down / `kill -9` while a swipe is held.
`WAN-LOSS-TEST.md` is the procedure for the same tests on the real barn: which of the three links is lost, how to cut it, what to check afterwards.

## Tests

`python -m pytest -q` (unit tests; no broker or backend needed; `test_usb_input.py` feeds key events through a pipe in the Linux evdev layout).
