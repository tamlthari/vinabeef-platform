# Barn edge gateway

Store-and-forward gateway that sits in the barn. **Local reads** (HTTP reads from sensors and USB swipes) go into a durable local queue first and are
forwarded to the VinaBeef backend from there, so a lost uplink or a down backend does not lose them.
The Safetrust MQTT feed is only **watched**: the backend already receives it directly, so the gateway never queues or forwards it.

```
HTTP POST :8090 (sensors, bridges) ─┐
USB readers (keyboard / serial) ────┼─► SQLite queue (WAL, synchronous=FULL) ─► forwarder ─► POST /api/v1/ingestion/batch
 POST :8090/local-swipe             ┘      ▲ deleted only after the backend confirms each read
                                           └ held a few seconds for the wall reader's own event (below), then sent, or dropped as a copy
Safetrust MQTT (or a LAN broker) ─────────► watched: remembered for matching, never queued, never sent
```

## Run

```bash
pip install -r requirements.txt
cp edge.environment.example .env   # fill in MQTT_PASSWORD, then:  set -a; . ./.env; set +a
python barn_edge_gateway.py
curl http://localhost:8090/status
```

Docker: `docker build -t barn-edge . && docker run -d --restart=always --env-file .env -v edge-data:/data -p 8090:8090 barn-edge`

**Every setting, the steps to configure and start it, what the console should show, and what to do when something is wrong: [`RUNBOOK.md`](RUNBOOK.md).**

## What it guarantees

* A local read is on disk (`synchronous=FULL`) before the gateway answers the HTTP POST or logs the USB swipe.
* An event is deleted only when the backend answers 200, or lists it as `OK` in a 207 reply. A failed event is kept and retried
  with backoff; after `EDGE_MAX_ATTEMPTS` it is marked `DEAD` (kept, visible in `/status`) so it cannot block the others.
* Events are sent oldest hardware time first. The backend is idempotent on `EVENT_KEY`, so a resend is harmless.
* The backend health probe checks its database, so the backlog is not replayed into a backend that cannot write.
* The MQTT client subscribes again after every reconnect, and keeps retrying if the first connection fails.
* The counters keep the two apart: **local reads** (`localReceived`, `localMatched`, `forwarded`, ...) and **the watched MQTT feed** (`mqttEvents`, `mqttRepeats`, `mqttOther`). `forwarded` is what was really sent to the cloud, and only local reads are ever sent.

## Where things are stored

* **The database**, `EDGE_DB_PATH` (default `edge_outbox.db` in the folder you start the gateway from). `edge_queue` is what is **waiting** to be sent (a read is deleted the moment the cloud confirms it, so it is usually empty); `swipe_log` is the **last 24 hours** of local reads and of the reader events seen on MQTT. Recent writes sit in the `edge_outbox.db-wal` side file until the gateway copies them into the `.db` file (every 2 s, and when it stops cleanly), so a tool that reads only the `.db` file can briefly show it empty.
* **The history log**, `EDGE_LOG_FILE` (default `edge_gateway.log` beside the database, 5 MB, 3 older files): link changes, what was sent, retries, refusals, each read taken. The console's own activity list and counters are in memory and start empty after a restart; this file survives it.
* The start-up log and the bottom of the console's Forward store print the **full paths**. The database path is relative to the folder you start from, so another folder means another, empty file.

## What it does not do

It protects the path **gateway → backend** for local reads. It does **not** back up the Safetrust MQTT feed: the backend subscribes to it directly, so if the
backend's own subscription misses an event while the barn link is down, the gateway does not replay it. The Safetrust readers talk only to the Safetrust cloud, so when
the *readers* cannot reach it (the barn has no Internet) Safetrust publishes nothing. **USB readers** next to the wall readers (below), or sensors that send HTTP reads
to the gateway, close that gap: they reach the gateway over a wire. The cloud copes with both at once.

## HTTP reads (sensors, Ethernet or serial bridges)

A sensor that knows the whole read posts it to `POST /`: a card UID, the reader name and a time (`{"TIMESTAMP": <ms>, "CARD_UUID": "042FAC02AD1490", "READER_NAME": "03F0"}`; an `EVENT_KEY` is optional, the gateway derives one from the content).
The gateway stores it, holds it while MQTT is up for the wall reader's own event (same reader and card, close on both clocks), and sends it unless that event arrived. The credential map does not apply: the read carries its own card.
A read with no reader name, or with neither a card nor a credential number, cannot be matched and is sent at once. The same `EVENT_KEY` again is a repeat and is ignored.
Use `python sensor_simulator.py` to play a few of these sensors (below).

## USB readers (Safetrust USB IoT)

A USB reader stands next to a wall reader and reports the same tap to **this gateway** over a wire, so it still works when the barn has
no Internet. It sends only a credential number (`38`, `1005`): no time, no card UUID. The gateway:

1. **stamps the time** with its own clock (`EDGE_TIME_SOURCE=GATEWAY_CLOCK`; keep the gateway's clock right, NTP) and gives the swipe a
   **negative `EVENT_KEY`** = `-(EDGE_GATEWAY_NO × 10^13 + counter)`. Safetrust keys are positive, so the two can never collide. The counter is
   persistent and never below the clock, so even a wiped database cannot reuse a key.
2. **stores it durably** at once (same file, same transaction style as every other event).
3. **holds it** for `LOCAL_HOLD_MS` (6 s) *only while MQTT is connected*, watching for the reader's own event for the tap on the MQTT feed (same reader and credential number).
   Event arrives → the swipe is a **shadow** (kept in the read log, not sent). None arrives → it is **queued** and forwarded.
   With MQTT down no event can reach this gateway, so there is nothing to wait for and the swipe is queued at once.
4. forwards it. **The cloud finds the card**: it looks up what card Safetrust itself reported with that credential number, then the tag register.
   A number it cannot place is kept as an *unmapped USB read* with an alert; it is processed as soon as the number becomes known (`POST /api/v1/ingestion/local/resolve`).
   **Or the gateway does**: `LOCAL_CREDENTIAL_MAP=36=042FAC02AD1490,38=044E6602AD1490` puts the card on the swipe (`CARD_UUID`, next to the `CREDENTIAL_IDENTIFIER`), and the cloud then
   reads it as an ordinary card read with no lookup. Meant for a demo or for numbers the cloud does not know yet. A number not in the list is not guessed: it goes on as before.
   A malformed entry, a number listed twice or one card for two numbers stops the gateway at start-up.

The link state is only a shortcut for step 3. It does **not** prove that no copy exists (the reader's own uplink can be down while the gateway's is up),
and the edge only sees events that reach *this* gateway by MQTT. The cloud knows two routes, the Safetrust feed and `LOCAL_USB`; an HTTP read that is not matched here reaches it as an ordinary read, as before. Everything else is decided in the cloud by the cross-route matcher
(`ingestion.cross-source.*`): same reader, same card, different route, reader TIMESTAMPs within the window → one movement, the second read kept as evidence.

Setup is `LOCAL_INPUTS` in `edge.environment.example`. `LOCAL_CREDENTIAL_MAP` (a typed `36` means card `042FAC02AD1490`) applies to **typed USB input only**. `kbd:` reads `/dev/input/eventX` directly and **grabs it exclusively** so the digits do not also land in a console
(the container needs `--device=/dev/input/eventX --group-add input`). `serial:` needs `pyserial` (`--device=/dev/ttyACM0 --group-add dialout`). A reader that sends no
Enter is handled: a swipe also ends after `LOCAL_IDLE_FLUSH_MS` of silence. Text that is not 1 to 12 digits is **rejected and counted** (`counters.localRejected`),
never guessed: a key the decoder does not know makes the swipe invalid instead of turning it into a different number.

**Direction needs two readers**, one at the inner and one at the outer wall reader (each USB reader is mapped with `=<reader name>`; an HTTP sensor names its reader). One reader gives presence only.
**OSDP is not implemented.** The Linux `kbd:` (evdev) and `serial:` code was only ever exercised with simulated devices, not with the real hardware.

`GET /status` → `local`: `held`, `queued`, `shadow` (HTTP and USB reads of the last 24 h, together), the inputs and whether each is connected, and `clockOffset`: the gateway's arrival time minus the reader's own `TIMESTAMP` over the last 50 reader events seen on MQTT
(median and largest). On a healthy link that is the reader clock error plus network delay: **use it to size the matching window** (`LOCAL_MATCH_SKEW_MS` here and `INGESTION_CROSS_SOURCE_WINDOW_MS` in the cloud).

## Run the demo

`edge.demo.env` is a ready set of settings for one gateway with a USB reader that types like a keyboard. From `edge_gateway/`:

```
pip install -r requirements.txt
set -a; . ./edge.demo.env; set +a; python barn_edge_gateway.py          # Linux / macOS
```

Open `http://localhost:8090/` (or `http://<this computer's IP>:8090/` from a phone on the same network). With `LOCAL_INPUTS=stdin=...` the reader types into the terminal the gateway runs in:
click that terminal, tap a tag, and the number and Enter arrive. On Linux, `kbd:/dev/input/eventX=...` reads the reader itself. The console only shows the link; the gateway connects to `CLOUD_BASE_URL`.

## Web console

Open `http://<gateway-ip>:8090/` in any browser on the barn network. It is read-only and has three parts:

- **Two connection cards**, side by side, each with its own state, reason and timing:
  **Cloud backend** (`CLOUD_BASE_URL`): Online, Degraded (it answers but cannot take events) or Offline; how long it has been that way, the last health check, the last event sent, the next retry and the failed tries in a row.
  **Reader feed (MQTT)** (`MQTT_URL`): Connected, Connecting, Not connected, Login refused or Not used; the broker, the client id, each topic and whether the broker confirmed it, how long, the last message and how many reader events were seen (none is sent by the gateway), and whether local reads wait for the reader's event.
  The two are independent: the backend state comes from the health check to `CLOUD_BASE_URL`, the reader feed from the MQTT session. A card warns when `MQTT_TOPICS` is empty, a topic is refused, the broker hangs up right after the login (before it confirms the subscription), or the connection keeps being dropped (another program with the same `MQTT_CLIENT_ID`).
  The gateway subscribes the way Safetrust's own clients do: one request for the token topic (`token/<token>`, taken from `MQTT_PASSWORD`) and `MQTT_TOPICS`, at `MQTT_QOS=2`, as client `Web_Edge_<MQTT_USERNAME>` unless `MQTT_CLIENT_ID` is set (use `Web_Edge_<device key>_<name>`, one per gateway). If the card says the broker hangs up after the login, check those three settings (`LOG_LEVEL=DEBUG` logs every MQTT packet with the token masked). The token is never printed or logged.
  **Check connection** walks the path to the backend step by step (address lookup, connection, secure handshake, health check); **Check broker path** does the first steps of an MQTT connection (address lookup, connection, secure handshake, WebSocket upgrade; it cannot log in). Each shows the time per step and a plain-language hint when one fails.
  They only ever ask the configured addresses, at most one check every two seconds, and do not touch the queue.
- **Forward store**: what is still on the gateway, by state (waiting, held for a reader copy, refused), searchable by card, reader or event number, in send order or newest first, with the full payload of any event. Sent events are deleted once the cloud confirms them, so they are not listed.
- **Synchronization**: local reads taken and sent per minute for the last 30 minutes (with a table view), the counters in two groups (**local reads, HTTP and USB together**, and **the reader feed, watched only**), and recent activity (sends, retries, refusals, link changes).
  The counters and this list live in memory and start empty after a restart; the store itself is durable and unaffected, and the same lines are in the history log file.

The console reads the store through its own `mode=ro` SQLite connection, so it cannot write and never blocks the intake or the forwarder. It has no buttons that change anything.
Set `EDGE_CONSOLE_TOKEN` to ask for a password (header `X-Edge-Token`; the page asks once per browser tab). Without it anyone on the LAN can open the console and read card numbers and event payloads (more than `/status` shows), so set it wherever the network is shared. The intake (`POST /`) is open on the LAN by default in the same way.
Behind a reverse proxy, keep `/api/` and `/` on the same host; the page makes only same-origin requests, loads nothing from the Internet and sets a content security policy.

## Endpoints

| | |
|---|---|
| `POST /` | one HTTP read or a list; `202 {"queued":n,"matched":k,"duplicates":m}` (`matched` = a copy of a reader event already seen on MQTT, not sent). Needs `X-Edge-Token` when `EDGE_INGEST_TOKEN` is set |
| `POST /local-swipe` | `{"reader":"DrC0F8","credential":"38","device":"kiosk-1"}` (device optional). The gateway stamps the time; the caller cannot. `202 {"state":"HELD\|QUEUED\|SHADOW","eventKey":-1…}`, `400` for a bad number or a reader this gateway does not serve. Same `X-Edge-Token` rule |
| `GET /status` | link state (`ONLINE`/`DEGRADED`/`OFFLINE`), MQTT state, queue depth (`held` = local reads waiting), oldest pending age, counters, `dbPath`, `local` (HTTP and USB reads) |
| `GET /health` | liveness |
| `GET /`, `GET /console` | the web console (HTML only; no data until its `/api/` calls succeed) |
| `GET /api/overview`, `GET /api/queue`, `GET /api/queue/<id>`, `POST /api/probe` (`?target=cloud` or `?target=mqtt`) | what the console reads. Need `X-Edge-Token` when `EDGE_CONSOLE_TOKEN` is set (not the ingest token). `/api/queue?status=PENDING\|HELD\|DEAD\|ALL&q=&order=oldest\|newest&limit=≤200&offset=` |

## Simulated sensors

`python sensor_simulator.py` plays a few sensors (`03F0`, `C1C4`, `0ED4`, `03EC`, `56CA`, `F9E2`) that post HTTP reads with random 14-digit hex tags (like `042FAC02AD1490`) to a running gateway.
Options: `--count`, `--interval`, `--sensors`, `--tag`, `--tags`, `--gateway`, `--token`, `--seed`. The reads are fake and are forwarded to whatever `CLOUD_BASE_URL` the gateway has: see [`RUNBOOK.md`](RUNBOOK.md) section 7.
