# Barn edge gateway: configure, run and check

The whole procedure for one gateway, from a folder of code to a working link. It is written for the computer in the barn, but every
step also works on a laptop. Section 7 shows how to try it with simulated sensors.

## 1. What you are setting up

```
 Wall readers ─► Safetrust cloud ─┬─► (a) cloud backend, directly, over its own MQTT subscription
                                  └─► MQTT broker ──(1) reader feed ──► GATEWAY   (watched only: never sent on)
 HTTP sensors (whole reads) ─────────────────────────────────────────────► GATEWAY ──(2) cloud backend ──► api.tamholthucwzr.men
 USB reader (types a number) ────────────────────────────────────────────► GATEWAY      │
                                                                                         └─ durable queue on disk (the "forward store")
```

**Only local reads are sent.** A *local read* is an HTTP read or a USB swipe; the two are counted together. The reader feed is only
**watched**: the cloud backend already receives those events itself (arrow a), so the gateway never queues or forwards them. It uses them for one
thing: to tell whether a local read is a copy of a tap the cloud already has. A read that matches a reader event is not sent. The console and
`/status` count the two apart (`local*` and `forwarded` against `mqtt*`).

The gateway keeps two connections and reports them **separately**, as two cards at the top of the console (`http://<gateway>:8090/`)
and as `mqtt.state` and `cloud.state` in `GET /api/overview`:

| | (1) Reader feed (MQTT) | (2) Cloud backend |
|---|---|---|
| Setting | `MQTT_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `MQTT_TOPICS` | `CLOUD_BASE_URL` |
| Direction | reader events **in**, watched only | local reads **out** |
| How the gateway judges it | the MQTT session with the broker: connected, and each topic confirmed | a health check, `GET <CLOUD_BASE_URL>/ingestion/health`, before every send, and the result of the send |
| States | Connected, Connecting, Not connected, Login refused, Not used | Online, Degraded (answers but cannot take events), Offline, Checking |
| When it is down | no reader events arrive, so a local read is sent at once instead of waiting up to 6 s for its reader copy | local reads wait in the store and are sent, oldest first, when it returns. Nothing is lost |
| Optional? | yes: HTTP and USB intake work without it | no |

One can be down while the other is up. A working Internet connection with the backend down still shows the backend as Offline or Degraded; an
unreachable Safetrust broker with a healthy backend still shows the backend as Online.

## 2. Before you start

| You need | Why |
|---|---|
| Python 3.12 (3.13 was used for the checks here) **or** Docker | to run the gateway |
| A correct clock on the gateway (NTP) | a local read and the reader's own event are the same tap only if their clocks are within `LOCAL_MATCH_SKEW_MS` (10 s) |
| Outbound HTTPS (443) to `api.tamholthucwzr.men` | the backend |
| Outbound WebSocket over 443 to `sensor.demo.safetrust.com` | the reader feed, only if you use it |
| Inbound port 8090 from the barn LAN | to open the console from a phone or another computer |
| The Safetrust API token | the MQTT password is `token/` followed by it. It is the token the backend already uses (`vinabeef.ingestion.safetrust.token` in the backend settings). The user name is the device key (`728589`) and the topic is `Web/<company key>/Monitoring/<device key>`, so `Web/475/Monitoring/728589` |
| The name of the Safetrust reader each USB reader or HTTP sensor stands next to | for example `F9E2`. It decides which wall reader the local read is matched with |
| A unique MQTT client id for this gateway | in the shape of the cloud's own subscribers, `Web_Direct_728589` (the backend) and `Web_Standby_728589` (the standby adapter): `Web_`, a name, the device key. For example `Web_Edge_728589_barn1`. Left blank, the gateway uses `Web_Edge_<MQTT_USERNAME>`. Two programs with the same id knock each other off the broker, so a second gateway needs its own suffix |

## 3. Install and start

1. **Get the code.** Everything is in `edge_gateway/` of the repository.
2. **Install the libraries** (a virtual environment is a good idea):
   ```
   cd edge_gateway
   pip install -r requirements.txt
   ```
3. **Pick a settings file.**

   | File | For | Reaches the real cloud? |
   |---|---|---|
   | `edge.demo.env` | the demo: a USB reader that types numbers, mapped to the four demo tags | **yes**: `https://api.tamholthucwzr.men/api/v1` |
   | `edge.environment.example` → copy to `.env` | the barn: Safetrust reader feed plus USB readers | **yes** |

   Never commit a filled-in `.env`: `MQTT_PASSWORD` is the Safetrust API token.
4. **Load the settings and start:**
   ```
   set -a; . ./edge.demo.env; set +a; python barn_edge_gateway.py          # Linux / macOS
   ```
   Docker (from `edge_gateway/`; `-it` is needed when the USB reader types into this terminal):
   ```
   docker build -t barn-edge .
   docker run --rm -it -p 8090:8090 -v edge-data:/data --env-file edge.demo.env barn-edge
   ```
   In Docker, `--env-file` takes `NAME=value` lines only (no `export`, no quotes). The `edge-data` volume keeps the store across restarts.
5. **Read the first lines of the log.** With the reader feed on, you should see (times and paths differ):
   ```
   INFO USB inputs: stdin->F9E2
   INFO MQTT: wss://sensor.demo.safetrust.com:443/mqtt as client 'Web_Edge_728589_barn1', subscribing to ['token/… (Safetrust token notices)', 'Web/475/Monitoring/728589'] at QoS 2
   INFO USB numbers mapped to cards: 36->042FAC02AD1490, 1005->04B86592C21290, 35->04143802F32290, 38->044E6602AD1490
   INFO Barn edge gateway up: HTTP 0.0.0.0:8090, backend https://api.tamholthucwzr.men/api/v1
   INFO Database (waiting reads and the read log): /home/pi/edge_gateway/edge_outbox.db
   INFO History log: /home/pi/edge_gateway/edge_gateway.log
   INFO MQTT connected to wss://sensor.demo.safetrust.com:443/mqtt; subscribing to ['token/… (Safetrust token notices)', 'Web/475/Monitoring/728589'] (QoS 2)
   INFO MQTT subscription granted QoS [2, 2]
   INFO Backend link UNKNOWN -> ONLINE
   ```
   These lines are worth a look every time you change a setting:
   * `MQTT_TOPICS is empty: ...` (warning): you are connected to the broker but subscribed to nothing.
   * `MQTT connect refused (rc=4)` or `(rc=5)`: the user name or password was refused.
   * `Backend link ... -> OFFLINE backend unreachable: ConnectError`: the backend cannot be reached.
   * `Database ...` and `History log ...` are the **full paths** of the two files that hold everything (section 10). Look at them once: a different folder means a different, empty file.
   * The log is quiet when all is well: there is no line for each health check.
6. **Open the console** at `http://localhost:8090/` (or `http://<gateway IP>:8090/`). Both cards should be green. Section 6 says how to read them.

## 4. Settings

All settings are environment variables. Anything not set takes the default shown.

### Backend (the cloud)

| Setting | Default | |
|---|---|---|
| `CLOUD_BASE_URL` | `http://localhost:8080/api/v1` | **Set this.** Where events go: `<url>/ingestion/batch`; health is `<url>/ingestion/health`. For the real cloud: `https://api.tamholthucwzr.men/api/v1` |
| `EDGE_BATCH_SIZE` | `100` | events per request |
| `EDGE_BACKOFF_MIN` / `EDGE_BACKOFF_MAX` | `2` / `60` seconds | wait before the next try after a failure; doubles each time up to the maximum, with a little random spread |
| `EDGE_MAX_ATTEMPTS` | `20` | an event the backend keeps refusing is marked Refused (kept, not retried) so it cannot block the others |
| `EDGE_MAX_QUEUE_ROWS` | `200000` | safety cap for very long outages |
| `EDGE_DB_PATH` | `edge_outbox.db` (Docker: `/data/edge_outbox.db`) | the store and the read log. **Relative to the folder you start the gateway from**, so give a full path in a settings file you start from anywhere. Keep it on storage that survives a reboot |
| `EDGE_LOG_FILE` | `edge_gateway.log` beside the database | the history of what the gateway did (links, sends, retries, reads), kept across restarts: 5 MB, 3 older files. Empty switches it off (the terminal only) |

### Reader feed (MQTT), watched only. Leave `MQTT_URL` empty to run without it

| Setting | Default | |
|---|---|---|
| `MQTT_URL` | empty | `wss://sensor.demo.safetrust.com:443/mqtt` for Safetrust, `mqtt://192.168.2.10:1883` for a broker on the barn LAN. Schemes: `ws`, `wss`, `mqtt`, `mqtts` |
| `MQTT_USERNAME` | empty | Safetrust: the device key, `728589` |
| `MQTT_PASSWORD` | empty | Safetrust: `token/` followed by the API token |
| `MQTT_TOPICS` | empty | comma separated. Safetrust: `Web/475/Monitoring/728589`. **Empty means subscribed to nothing** |
| `MQTT_CLIENT_ID` | `Web_Edge_<MQTT_USERNAME>` | **unique per program on the broker**, in the shape `Web_<name>_<device key>[_<suffix>]` like the cloud's own (`Web_Direct_728589`). A second gateway on the same account needs its own suffix |
| `MQTT_QOS` | `2` | what the cloud's own subscribers ask for. If the broker refuses a QoS for a topic, the gateway asks again for that topic at QoS 0 |
| `MQTT_TOKEN_TOPIC` | `auto` | the cloud's own subscribers subscribe to `token/<API token>` as well as the topic, in the same request, and the broker sends token notices there. `auto` does the same when the password starts with `token/`; `false` never does; the console shows it as "token/… (Safetrust token notices)", never the token itself |
| `MQTT_CLEAN_SESSION` | `true` | `false` asks the broker to keep messages while this gateway is off (depends on the broker) |
| `MQTT_TLS_INSECURE` | `true` | do not check the broker's certificate. The Safetrust demo broker needs it; use `false` with a real CA |
| `MQTT_CA_CERTS` | empty | CA file to check the broker with when `MQTT_TLS_INSECURE=false` |

### USB readers (Safetrust USB IoT)

| Setting | Default | |
|---|---|---|
| `LOCAL_INPUTS` | empty | the USB readers: `<kind>:<device>=<wall reader name>`, comma separated. `stdin=DrC0F8` (type in the terminal the gateway runs in), `kbd:/dev/input/event5=DrC0F8` (the reader as a keyboard; Linux only; the gateway takes the device exclusively), `serial:/dev/ttyACM0@9600=DrC1C4` (needs `pyserial`). Find the keyboard device with `ls -l /dev/input/by-id/`. **The name after `=` must be the real wall reader at that gate** |
| `LOCAL_READERS` | empty | extra reader names `POST /local-swipe` may use (the inputs above are added automatically). HTTP reads (`POST /`) name their own reader and need nothing here |
| `LOCAL_CREDENTIAL_MAP` | empty | `36=042FAC02AD1490,38=044E6602AD1490`: the card UUID of a **typed USB number**, for the demo or for numbers the cloud does not know. It applies to typed USB input only: an HTTP read carries its own card. A number not listed is not guessed. A bad entry, a repeated number, or one card for two numbers stops the gateway at start-up |
| `EDGE_GATEWAY_NO` | `1` | 1 to 99, unique per gateway; keeps the negative event numbers of USB swipes apart |
| `LOCAL_HOLD_MS` | `6000` | how long a local read (HTTP or USB) waits for the wall reader's own event while the reader feed is connected (`0` = never wait) |
| `LOCAL_MATCH_SKEW_MS` | `10000` | two reads are the same tap when closer than this on the reader's clock **and** the gateway's |
| `LOCAL_IDLE_FLUSH_MS` | `200` | a USB device that sends no Enter ends a swipe after this silence |
| `LOCAL_LOG_RETENTION_S` | `86400` | how long the read log is kept (local reads and the reader events seen) |
| `LOCAL_COMPANY_KEY` | `0` | optional `COMPANY_KEY` copied onto USB swipes (`0` = none) |
| `LOCAL_GRAB_KEYBOARD` | `true` | `false` only for testing: the digits would also be typed into the gateway's console |

**Which kind of input for which computer.** The gateway recognises a USB reader by the device it is plugged into, and the name after `=` says which Safetrust reader that device stands next to. It does not use the sender's network address.

| Computer | Reader works as | `LOCAL_INPUTS` | Notes |
|---|---|---|---|
| Linux | a keyboard | `kbd:/dev/input/by-id/<entry ending -event-kbd>=F9E2` | the gateway takes the device exclusively, so the digits never reach a terminal. Plug the reader in and run `ls -l /dev/input/by-id/` to find the entry; your user must be in the `input` group. `by-id` survives a reboot, `eventNN` does not |
| macOS or Windows | a keyboard | `stdin=F9E2` | **`kbd:` does not work here** (it reads `/dev/input`, which only Linux has; the gateway says so in the log and in the console). The reader types into the terminal the gateway runs in, so that window must have focus when you tap |
| any | a serial (CDC) port | `serial:/dev/cu.usbmodem1101@9600=F9E2` (macOS), `serial:COM3@9600=F9E2` (Windows), `serial:/dev/ttyACM0@9600=F9E2` (Linux) | needs `pip install pyserial` and a reader set to serial mode. On a Mac `ls /dev/cu.usb*` shows whether it appears; in Device Manager on Windows it is under Ports (COM). This is the way to read a reader in the background on macOS or Windows |
| another computer on the LAN | either | that computer sends `POST /local-swipe` with `"reader": "F9E2"` | the reader name in the request decides, and `F9E2` must be listed in `LOCAL_READERS`. Set `EDGE_INGEST_TOKEN` so only your own computers can send |

After you start the gateway, the log has a `USB inputs:` line. If it says `USB inputs: none`, `LOCAL_INPUTS` was empty when the gateway started: the settings file was not loaded, or the line is still commented out. The console's Synchronization section shows the same, and says why a reader is not listening.

### The gateway itself

| Setting | Default | |
|---|---|---|
| `EDGE_BIND_HOST` | `0.0.0.0` | `127.0.0.1` keeps it to this computer |
| `EDGE_BIND_PORT` | `8090` | intake, `/status` and the console |
| `EDGE_CONSOLE_TOKEN` | empty | password for the console (header `X-Edge-Token`). Empty means anyone on the LAN can open the console and read card numbers |
| `EDGE_INGEST_TOKEN` | empty | if set, `POST /` and `POST /local-swipe` need the header `X-Edge-Token: <value>`. Independent of the console password |
| `LOG_LEVEL` | `INFO` | `DEBUG` also logs every health check |

## 5. Which settings for which job

* **Show the demo with typed numbers.** `edge.demo.env` as it is: `LOCAL_INPUTS=stdin=F9E2` names the Safetrust IoT USB reader F9E2. Change the name after `=` to the reader at your gate. The reader
  feed stays off, so the console shows it as Not used and a typed number goes out at once.
* **Demo plus the wall readers.** Remove the `#` from the five `MQTT_` lines in `edge.demo.env` and paste the token. A typed number or an HTTP read now
  waits up to 6 s for the wall reader's event and, if it arrives, is not sent: the cloud already has the wall reader's own event.
* **The barn.** Copy `edge.environment.example` to `.env`, paste the token, set `MQTT_CLIENT_ID` to something unique, set `LOCAL_INPUTS` to the
  real devices and readers, set `EDGE_CONSOLE_TOKEN`, and keep `EDGE_DB_PATH` on storage that survives a reboot. Run it under Docker with
  `--restart=always` (and `--device=/dev/input/eventX --group-add input` for a keyboard-style reader).

## 6. Read the console

Both cards say what is wrong in words, how long it has been that way, and what the gateway is doing about it. **Check connection** (backend) and
**Check broker path** (broker) walk the path step by step: address lookup, connection, secure handshake, and then the health check or the WebSocket upgrade.
Neither changes anything; the broker check cannot log in, because the card above it already shows whether the login worked.

**Cloud backend**

| Shows | Means | Do |
|---|---|---|
| Online | the health check passes; events go out as they arrive | nothing |
| Degraded | the backend answers but its health check fails (HTTP 5xx, usually its database) | events are safe and retried. Look at the backend |
| Offline | no connection, or sending failed. The reason is in brackets, for example `ConnectError` | press **Check connection**: it names the step that fails and says what to look at |
| Checking | no answer yet since the gateway started | wait a few seconds |

**Reader feed (MQTT)**

| Shows | Means | Do |
|---|---|---|
| Connected, topics "subscribed, QoS 2" | logged in and watching | nothing. "Last message: None yet" is normal until a reader is tapped. The events counted here are **not** sent by the gateway |
| Connected, with a warning | `MQTT_TOPICS` is empty, the broker refused a topic, or the connection keeps being dropped | the warning says which and what to change |
| Connecting (n tries so far) | the broker has not answered yet | press **Check broker path**; wait if the Internet has just come back |
| Not connected | it was connected and the connection was lost | the gateway retries on its own, every 1 to 60 s, and subscribes again |
| Login refused | the broker answered and said no | check `MQTT_USERNAME`, `MQTT_PASSWORD` (`token/...`) and `MQTT_CLIENT_ID`; the token may have expired |
| Not used | `MQTT_URL` is empty | by design |

The same numbers for scripts, no password needed unless `EDGE_CONSOLE_TOKEN` is set: `curl http://localhost:8090/api/overview` (fields `cloud.state`, `mqtt.state`).
`curl http://localhost:8090/status` is the short form: `wan` (the backend), `mqttConnected`, the queue, the counters, and `local.clockOffset` (how far the
readers' clocks are from this one; the matching window must be larger than that).

## 7. Try it with simulated sensors

`sensor_simulator.py` plays sensors that deliver whole reads (a card UID, a reader name, a time) to the gateway's HTTP door, the way a LAN sensor, an
Ethernet reader or a serial-to-HTTP bridge would. It needs only Python. The sensors are `03F0`, `C1C4`, `0ED4`, `03EC`, `56CA` and `F9E2`, and the tags
are random 14-digit hex UIDs starting with `04` (like `042FAC02AD1490`). The credential map plays no part: it is for typed USB numbers only.

**The reads are fake, and the gateway forwards them to `CLOUD_BASE_URL`.** Point it at a test backend, or accept that the cloud you configured gets
rows for tags nobody owns. The simulator waits 3 s before it starts so you can stop it.

1. Start the gateway (section 3).
2. In a second terminal, from `edge_gateway/`:
   ```
   python sensor_simulator.py                                   # one read every ~2 s, six sensors, until Ctrl-C
   python sensor_simulator.py --count 20 --interval 0.5         # twenty quick ones
   python sensor_simulator.py --sensors 03F0,C1C4 --tag 042FAC02AD1490   # two sensors, one known tag
   ```
   `--gateway`, `--token` (if `EDGE_INGEST_TOKEN` is set), `--tags` (how many random tags to reuse), `--seed` and `--yes` are the other options; `--help` lists them.

| Try | You should see |
|---|---|
| **The reader feed is off** (`MQTT_URL` empty). Run the simulator | each read is sent at once. The console's **Local reads taken** and **Sent to the cloud** go up together; the **Reader feed** counters stay at 0 |
| **The reader feed is on.** Run the simulator | each read waits up to 6 s (`LOCAL_HOLD_MS`) under **Forward store → Held**, then is sent. A read whose own wall-reader event arrives in that time is **Matched with a reader event, not sent**. The **Reader feed** counters count the wall readers' events separately, and none of them is ever sent |
| **A typed number.** `LOCAL_INPUTS=stdin=F9E2`: click the gateway's terminal, type `36` and Enter | `USB swipe: credential 36 at reader F9E2 from stdin -> ...` in the log. With the credential map the sent event carries card `042FAC02AD1490`, a negative event number and `"EDGE_SOURCE": "LOCAL_USB"`. A number that is not 1 to 12 digits is refused and counted, never sent |
| **The cloud is unreachable.** Stop the backend, or start the gateway with `CLOUD_BASE_URL=http://127.0.0.1:9/api/v1`, then run the simulator | the **Cloud backend** card turns Offline; the reads appear under **Forward store → Waiting** and are in the database file (section 10). Bring the cloud back: they are sent oldest first and leave the store |
| **A power cut.** With the cloud unreachable and reads waiting: `pkill -9 -f barn_edge_gateway.py`, then start the gateway again | the reads are still under Waiting, and are sent once the cloud answers |
| **The same read twice.** `curl -s -X POST http://localhost:8090/ -H 'Content-Type: application/json' -d '{"EVENT_KEY":900001,"TIMESTAMP":1760000000000,"CARD_UUID":"042FAC02AD1490","READER_NAME":"03F0"}'`, twice | the second answer says `"duplicates": 1` and **Repeats ignored** goes up by one |

## 8. Against the real cloud and the real readers

1. Every local read the gateway takes is forwarded to `CLOUD_BASE_URL`. Pointed at the real cloud, a typed `36` is a real movement for the animal with card `042FAC02AD1490`. Use test tags you are happy to see in the dashboard.
2. Start with the demo settings, reader feed off, and type one number: the Cloud backend card is Online, the row appears under Waiting only if you cut the link, and the movement appears in the dashboard.
3. Turn the reader feed on (section 5). Tap a tag on the **wall reader** only: the Reader feed card's "Last message" shows it, and the event appears in the dashboard once.
4. Type the number on the USB reader and tap the wall reader within a second or two. The dashboard should show **one** movement. If it shows two, check the reader name after `=` in `LOCAL_INPUTS` and `local.clockOffset` in `/status` (it must be well under 10 s). An HTTP sensor behaves the same: its read is matched with the wall reader's event by reader name and card.
5. Unplug the barn's Internet for a minute while tapping: both cards go red, the typed numbers wait under Waiting, and when the link returns they are sent in order and the dashboard fills in.
6. If you use `kbd:` readers, confirm that digits typed on the USB reader do **not** appear in any terminal (`LOCAL_GRAB_KEYBOARD=true`, the default).

## 9. When something is wrong

| You see | Probably | Do |
|---|---|---|
| Reader feed stays **Connecting** | the broker is unreachable: no Internet, DNS, a firewall on 443, or a wrong `MQTT_URL`. The MQTT client does not use `HTTPS_PROXY` | **Check broker path**: it names the step that fails |
| Reader feed **Login refused** | wrong `MQTT_USERNAME`/`MQTT_PASSWORD`, an expired token, or a refused `MQTT_CLIENT_ID` | the card gives the broker's reason; paste the token again, with the `token/` prefix |
| Reader feed connects and drops again and again, with a warning about `MQTT_CLIENT_ID` | another program uses the same client id | give this gateway its own (`Web_Edge_728589_<name>`) |
| The log shows `MQTT connected` and, about a quarter of a second later, `MQTT disconnected (rc=7)`, over and over, and the card says the broker hangs up right after the login | the login is accepted, then the broker closes the connection without answering the subscription. It does not like the client id, the subscription or its QoS. The cloud's own subscribers use an id ending in the device key, subscribe to `token/<API token>` and the topic together, and ask for QoS 2, which are this gateway's defaults now | set `MQTT_CLIENT_ID`, `MQTT_QOS` and `MQTT_TOKEN_TOPIC` as above, and try `LOG_LEVEL=DEBUG`: it logs every MQTT packet (the token masked) |
| Reader feed **Connected** but "Last message: None yet" while readers are being tapped | wrong topic (company or device key), or the readers' own uplink is down so Safetrust publishes nothing | check `MQTT_TOPICS`; look at the Safetrust portal. Local reads still work |
| A topic says "refused by the broker" | the topic is wrong or the account may not read it | correct `MQTT_TOPICS` |
| Cloud backend **Offline** | no Internet, DNS, firewall, a wrong `CLOUD_BASE_URL`, a proxy in the environment, or the backend is down | **Check connection**. A secure-handshake failure usually means the gateway's date is wrong |
| Cloud backend **Degraded** | the backend is up but its health check fails | look at the backend's database and logs; events are kept |
| Events under **Refused** in the store | the backend refuses them (for example HTTP 422) | open the row: the last error is there. They are kept and no longer retried |
| One tap makes two movements | the reader name after `=` in `LOCAL_INPUTS` (or in the HTTP read) is not the real wall reader, or the clocks differ by more than 10 s | fix the name; fix the clock (NTP); read `local.clockOffset` in `/status` |
| A typed number does nothing | `stdin`: the terminal is not in focus. `kbd`: no permission on the device. The text is not 1 to 12 digits (counted as rejected) | click the terminal; add the device and `--group-add input` in Docker; read `counters.localRejected` |
| I open `edge_outbox.db` and see no tables or no rows | the file is not the one the gateway uses (another folder, another `EDGE_DB_PATH`), the viewer ignores the `-wal` side file, or the reads were already sent (the queue holds only what is waiting) | section 10: the start-up log and the console's Forward store footer give the full path; the gateway copies the `-wal` into the `.db` every 2 s; `swipe_log` keeps the last 24 h of reads |
| `reader 'X' is not served by this gateway` | the name is not in `LOCAL_INPUTS` or `LOCAL_READERS` | add it |
| The gateway stops at start-up with a message about the credential map | a malformed entry, a repeated number, or one card for two numbers in `LOCAL_CREDENTIAL_MAP` | fix the list |
| `DeprecationWarning: Callback API version 1 is deprecated` at start | a notice from paho-mqtt 2.x | harmless |
| The console looks old after you edit `console.html` | the gateway caches the page | restart the gateway |
| `Address already in use` | something else uses `EDGE_BIND_PORT` | change it |

## 10. Daily use and where things are kept

* **Stop:** Ctrl-C, or `docker stop`. The store is on disk; nothing waiting is lost.
* **Start again:** the same command. Events still in the store are sent first.
* **Update:** copy the new files over `edge_gateway/`, restart. The store file is untouched.
* **Where things are kept.** Two files, and their full paths are in the start-up log and at the bottom of the console's Forward store:
  * **The database** (`EDGE_DB_PATH`, default `edge_outbox.db` in the folder you start from). Table `edge_queue` is what is **waiting** to be sent: a local read is added the moment it arrives and deleted the moment the cloud confirms it, so with the backend Online it is nearly always empty. Table `swipe_log` is a **history of the last 24 hours**: every local read (`route` `LOCAL_USB` or `LOCAL_HTTP`, with its state: `HELD`, `QUEUED` when it was sent, `SHADOW` when it matched a reader event) and every reader event seen on MQTT (`route` `SAFETRUST`, which is never sent). `edge_meta` holds the USB event counter.
  * **The history log** (`EDGE_LOG_FILE`, default `edge_gateway.log` beside the database): the lines the terminal shows (link changes, what was sent, retries, refusals, each read taken), kept across restarts. The console's own activity list and counters are in memory and start empty after a restart; this file does not.
* **The `-wal` side file.** SQLite writes new rows to `edge_outbox.db-wal` first. The gateway copies them into `edge_outbox.db` every 2 s and when it stops cleanly, so the `.db` file is complete within a couple of seconds. A tool that reads only the `.db` file during those seconds, or after a hard stop, can show it empty; `sqlite3` reads both files together.
* **Look inside.** From the folder that holds the file:
  ```
  sqlite3 edge_outbox.db "select seq_id, status, attempts, substr(payload_json, 1, 90) from edge_queue"
  sqlite3 edge_outbox.db "select datetime(arrived_ms/1000,'unixepoch','localtime'), route, reader, credential, card, state from swipe_log order by id desc limit 20"
  ```
  To see a row in `edge_queue`, make the backend unreachable first (`CLOUD_BASE_URL=http://127.0.0.1:9/api/v1`, or stop the backend) and send a read: it appears as `PENDING`, and goes when the backend returns.
* **Reset the store** (it deletes the reads still waiting and the 24-hour read log): stop the gateway, delete `edge_outbox.db`, `edge_outbox.db-wal` and `edge_outbox.db-shm`.
* The console's activity history (what was sent, retried, refused, and the connection changes) lives in memory and starts empty after a restart. The store and the history log file do not.
