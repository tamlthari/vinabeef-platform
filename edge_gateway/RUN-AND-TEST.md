# Barn edge gateway: configure, run and test

The whole procedure for one gateway, from a folder of code to a tested link. It is written for the computer in the barn, but every
step also works on a laptop. Section 7 is a test bench that touches neither the real cloud nor the Safetrust broker, so you can try
everything first.

## 1. What you are setting up

```
 Wall readers ─► Safetrust cloud ─► MQTT broker ──(1) reader feed ──┐
                                                                     ├─► GATEWAY ──(2) cloud backend ──► api.tamholthucwzr.men
 USB reader (types a number) ────────────────────────────────────────┘      │
                                                                            └─ durable queue on disk (the "forward store")
```

The gateway keeps two connections and reports them **separately**, as two cards at the top of the console (`http://<gateway>:8090/`)
and as `mqtt.state` and `cloud.state` in `GET /api/overview`:

| | (1) Reader feed (MQTT) | (2) Cloud backend |
|---|---|---|
| Setting | `MQTT_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `MQTT_TOPICS` | `CLOUD_BASE_URL` |
| Direction | reader events **in** | events **out** |
| How the gateway judges it | the MQTT session with the broker: connected, and each topic confirmed | a health check, `GET <CLOUD_BASE_URL>/ingestion/health`, before every send, and the result of the send |
| States | Connected, Connecting, Not connected, Login refused, Not used | Online, Degraded (answers but cannot take events), Offline, Checking |
| When it is down | no reader copies arrive, so a typed USB number is queued at once instead of waiting up to 6 s for one | events wait in the store and are sent, oldest first, when it returns. Nothing is lost |
| Optional? | yes: USB and HTTP intake work without it | no |

One can be down while the other is up. A working Internet connection with the backend down still shows the backend as Offline or Degraded; an
unreachable Safetrust broker with a healthy backend still shows the backend as Online.

## 2. Before you start

| You need | Why |
|---|---|
| Python 3.12 (3.13 was used for the checks here) **or** Docker | to run the gateway |
| A correct clock on the gateway (NTP) | a USB swipe and the reader's own copy are the same tap only if their clocks are within `LOCAL_MATCH_SKEW_MS` (10 s) |
| Outbound HTTPS (443) to `api.tamholthucwzr.men` | the backend |
| Outbound WebSocket over 443 to `sensor.demo.safetrust.com` | the reader feed, only if you use it |
| Inbound port 8090 from the barn LAN | to open the console from a phone or another computer |
| The Safetrust API token | the MQTT password is `token/` followed by it. It is the token the backend already uses (`vinabeef.ingestion.safetrust.token` in the backend settings). The user name is the device key (`728589`) and the topic is `Web/<company key>/Monitoring/<device key>`, so `Web/475/Monitoring/728589` |
| The name of the Safetrust reader each USB reader stands next to | for example `DrC0F8`. It decides which wall reader the USB swipe is matched with |
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
   | `edge.bench.env` | trying everything safely: fake backend, local broker, typed numbers (section 7) | no |
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
   INFO Barn edge gateway up: HTTP 0.0.0.0:8090, queue edge_outbox.db, backend https://api.tamholthucwzr.men/api/v1
   INFO MQTT connected to wss://sensor.demo.safetrust.com:443/mqtt; subscribing to ['token/… (Safetrust token notices)', 'Web/475/Monitoring/728589'] (QoS 2)
   INFO MQTT subscription granted QoS [2, 2]
   INFO Backend link UNKNOWN -> ONLINE
   ```
   These lines are worth a look every time you change a setting:
   * `MQTT_TOPICS is empty: ...` (warning): you are connected to the broker but subscribed to nothing.
   * `MQTT connect refused (rc=4)` or `(rc=5)`: the user name or password was refused.
   * `Backend link ... -> OFFLINE backend unreachable: ConnectError`: the backend cannot be reached.
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
| `EDGE_DB_PATH` | `edge_outbox.db` (Docker: `/data/edge_outbox.db`) | the store. Keep it on storage that survives a reboot |

### Reader feed (MQTT). Leave `MQTT_URL` empty to run without it

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
| `LOCAL_INPUTS` | empty | `<kind>:<device>=<wall reader name>`, comma separated. `stdin=DrC0F8` (type in the terminal the gateway runs in), `kbd:/dev/input/event5=DrC0F8` (the reader as a keyboard; Linux only; the gateway takes the device exclusively), `serial:/dev/ttyACM0@9600=DrC1C4` (needs `pyserial`). Find the keyboard device with `ls -l /dev/input/by-id/`. **The name after `=` must be the real wall reader at that gate** |
| `LOCAL_READERS` | empty | extra reader names `POST /local-swipe` may use (the inputs above are added automatically) |
| `LOCAL_CREDENTIAL_MAP` | empty | `36=042FAC02AD1490,38=044E6602AD1490`: the card UUID of a typed number, for the demo or for numbers the cloud does not know. A number not listed is not guessed. A bad entry, a repeated number, or one card for two numbers stops the gateway at start-up |
| `EDGE_GATEWAY_NO` | `1` | 1 to 99, unique per gateway; keeps the negative event numbers of USB swipes apart |
| `LOCAL_HOLD_MS` | `6000` | how long a typed number waits for the wall reader's own copy while the reader feed is connected (`0` = never wait) |
| `LOCAL_MATCH_SKEW_MS` | `10000` | two reads are the same tap when closer than this on the reader's clock **and** the gateway's |
| `LOCAL_IDLE_FLUSH_MS` | `200` | a USB device that sends no Enter ends a swipe after this silence |
| `LOCAL_LOG_RETENTION_S` | `86400` | how long the swipe log is kept |
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
* **Demo plus the wall readers.** Remove the `#` from the five `MQTT_` lines in `edge.demo.env` and paste the token. A typed number now
  waits up to 6 s for the wall reader's copy and, if it arrives, only the wall reader's copy is sent.
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
| Connected, topics "subscribed, QoS 2" | logged in and receiving | nothing. "Last message: None yet" is normal until a reader is tapped |
| Connected, with a warning | `MQTT_TOPICS` is empty, the broker refused a topic, or the connection keeps being dropped | the warning says which and what to change |
| Connecting (n tries so far) | the broker has not answered yet | press **Check broker path**; wait if the Internet has just come back |
| Not connected | it was connected and the connection was lost | the gateway retries on its own, every 1 to 60 s, and subscribes again |
| Login refused | the broker answered and said no | check `MQTT_USERNAME`, `MQTT_PASSWORD` (`token/...`) and `MQTT_CLIENT_ID`; the token may have expired |
| Not used | `MQTT_URL` is empty | by design |

The same numbers for scripts, no password needed unless `EDGE_CONSOLE_TOKEN` is set: `curl http://localhost:8090/api/overview` (fields `cloud.state`, `mqtt.state`).
`curl http://localhost:8090/status` is the short form: `wan` (the backend), `mqttConnected`, the queue, the counters, and `local.clockOffset` (how far the
readers' clocks are from this one; the matching window must be larger than that).

## 7. Test bench: nothing reaches the real cloud

This runs the gateway against a **fake backend** and a **local MQTT broker**, and you play the USB reader and the wall reader yourself.
You need three terminals in `edge_gateway/`, and `pip install amqtt` once for the broker.

| Terminal | Command | Why |
|---|---|---|
| 1, the cloud | `echo ok > /tmp/bench.mode; : > /tmp/bench.accepted; python drill/fake_backend.py 18080 /tmp/bench.mode /tmp/bench.accepted` | a stand-in backend. What it answers is whatever is in `/tmp/bench.mode`: `ok`, `down503` (health fails: Degraded), `hang` (no answer: Offline), `health_ok_write_503` (health passes, writes fail). Everything it accepts is appended to `/tmp/bench.accepted` |
| 2, the broker | `python drill/broker.py 1883` | a stand-in for Safetrust. Optional: skip it to test only the USB and HTTP paths |
| 3, the gateway | `set -a; . ./edge.bench.env; set +a; python barn_edge_gateway.py` | the gateway, with the demo mapping and `stdin=DrC0F8` |

Open `http://localhost:8090/`. Then watch `tail -f /tmp/bench.accepted` in a fourth terminal as you go.

**T1. The baseline.** Both cards are green: Cloud backend **Online**, Reader feed **Connected**, topics "subscribed, QoS 2". The log has the lines from step 3.5.

**T2. An event over HTTP.**
```
curl -s -X POST http://localhost:8090/ -H 'Content-Type: application/json' \
  -d "{\"EVENT_KEY\":900001,\"TIMESTAMP\":$(date +%s)000,\"CARD_UUID\":\"042FAC02AD1490\",\"READER_NAME\":\"DrC0F8\",\"NOTES\":{\"ACTION\":\"TAP\"}}"
```
Expect `{"status": "BUFFERED_IN_EDGE_WAL", "queued": 1, ...}` and, a second later, the event in `/tmp/bench.accepted`. The console's "Received" and "Sent to the cloud" counters each go up by one.

**T3. A typed number (the USB reader).** Click terminal 3, type `36` and Enter. Expect `USB swipe: credential 36 at reader DrC0F8 from stdin -> HELD` in the log. The reader feed is
connected, so the gateway waits 6 s for a wall-reader copy that does not come, then sends the swipe. In `/tmp/bench.accepted` the line has `"card": "042FAC02AD1490"`, `"cred": "36"`, a **negative** `key`
and `"src": "LOCAL_USB"`. Try the other three: `1005`, `35`, `38`. A number that is not 1 to 12 digits is rejected and counted, never sent.

**T4. One tap seen twice becomes one event.** Type `35` and Enter, and within 6 s run in another terminal:
```
python drill/publish_reader_event.py --credential 35 --card 04143802F32290
```
Expect only the wall reader's copy in `/tmp/bench.accepted` (positive `key`, no `src`), and on the console, under Synchronization, **USB swipes matched to a reader copy** goes up by one. Run it again with `--reader SomeOtherReader`:
the names differ, nothing is paired, and **both** are sent. That is what a wrong name after `=` in `LOCAL_INPUTS` does at the barn.

**T5. The backend goes away and comes back.**
1. `echo down503 > /tmp/bench.mode`: within about a second the Cloud backend card turns **Degraded** ("its health check returned HTTP 503"). The Reader feed card stays Connected.
2. `echo ok > /tmp/bench.mode`: back to Online.
3. Stop terminal 1 with Ctrl-C: the card turns **Offline** ("no connection (ConnectError)") and "Next try" counts down. A silent backend (`echo hang > /tmp/bench.mode`) takes up to 10 s to show, the request timeout.
4. While it is Offline, type `38` and Enter in terminal 3. After 6 s the number appears under **Forward store → Waiting**, with its card `044E6602AD1490`. Open the row to see the full event. Press **Check connection** to see the step that fails.
5. Start terminal 1 again (use the same command but without `: > /tmp/bench.accepted;` so the earlier lines are kept). The card returns to Online and the store empties, oldest first. The row is gone from the store and the event is in `/tmp/bench.accepted`.

**T6. The broker goes away and comes back.** Stop terminal 2 with Ctrl-C. The Reader feed card turns **Not connected**: "The connection to the broker was lost: ...", the tries count goes up, and **USB swipes** says **Queued at once**.
The Cloud backend card stays Online, and a number typed now is sent at once. Start the broker again: the card returns to **Connected**, the topic is confirmed again, and the activity log shows the loss and the recovery.
Press **Check broker path** while the broker is stopped: it stops at "Open a connection" and says the port is closed or firewalled.

**T7. A power cut.** Stop terminal 1, type `38` in terminal 3, wait for it to appear under Waiting, then kill the gateway hard: `pkill -9 -f barn_edge_gateway.py`. Start it again (terminal 3). The number is still under Waiting. Start the backend: it is sent once.

**T8. A wrong password and an empty topic list.** The bench broker accepts anyone, so use the Safetrust settings for this one. Put a wrong token in `MQTT_PASSWORD` and start.
A broker that answers a bad login with a refusal (MQTT code 4 or 5) makes the card say **Login refused**, with advice to check the user name and password, and the log say `MQTT connect refused (rc=4)`.
If the broker rejects the connection at an earlier step, the card stays on **Connecting** instead: press **Check broker path** to see how far it gets. Then set `MQTT_TOPICS=` (empty) with a good token: the card warns that nothing is subscribed.

**T9. The console password.** Add `EDGE_CONSOLE_TOKEN=secret` and restart: the page asks for the token once per browser tab, and `curl http://localhost:8090/api/overview` answers 401 until you add `-H 'X-Edge-Token: secret'`.

Clean up with Ctrl-C in each terminal and `rm bench_outbox.db*` (and `rm /tmp/bench.mode /tmp/bench.accepted`).

## 8. Against the real cloud and the real readers

1. Everything that reaches the gateway is forwarded to `CLOUD_BASE_URL`. Pointed at the real cloud, a typed `36` is a real movement for the animal with card `042FAC02AD1490`. Use test tags you are happy to see in the dashboard.
2. Start with the demo settings, reader feed off, and type one number: the Cloud backend card is Online, the row appears under Waiting only if you cut the link, and the movement appears in the dashboard.
3. Turn the reader feed on (section 5). Tap a tag on the **wall reader** only: the Reader feed card's "Last message" shows it, and the event appears in the dashboard once.
4. Type the number on the USB reader and tap the wall reader within a second or two. The dashboard should show **one** movement. If it shows two, check the reader name after `=` in `LOCAL_INPUTS` and `local.clockOffset` in `/status` (it must be well under 10 s).
5. Unplug the barn's Internet for a minute while tapping: both cards go red, the typed numbers wait under Waiting, and when the link returns they are sent in order and the dashboard fills in.
6. If you use `kbd:` readers, confirm that digits typed on the USB reader do **not** appear in any terminal (`LOCAL_GRAB_KEYBOARD=true`, the default).

## 9. Automated tests

| Command | What it covers | Needs |
|---|---|---|
| `python -m pytest -q` | all unit tests: intake, queue, forwarder, USB decoding and pairing, the console API, the two connection states | `pip install pytest` |
| `python drill/wan_drill.py` | a real gateway against a real local broker and a fake backend: backend down, 503, hanging, `kill -9`, duplicate events, broker lost and back | `pip install amqtt paho-mqtt httpx`; ports 18830, 18080, 18090 free |
| `python drill/mqtt_probe.py` | not a test of the code: asks YOUR broker which client id, QoS and subscription it accepts (section 10). Stop the gateway first | `pip install paho-mqtt`; your `.env` loaded; reaches the broker |
| `python drill/usb_drill.py` | the USB path the same way: held, shadow, no copy, backend down, broker lost while a swipe is held, `kill -9` | same; ports 18930, 18180, 18190 free |

## 10. When something is wrong

| You see | Probably | Do |
|---|---|---|
| Reader feed stays **Connecting** | the broker is unreachable: no Internet, DNS, a firewall on 443, or a wrong `MQTT_URL`. The MQTT client does not use `HTTPS_PROXY` | **Check broker path**: it names the step that fails |
| Reader feed **Login refused** | wrong `MQTT_USERNAME`/`MQTT_PASSWORD`, an expired token, or a refused `MQTT_CLIENT_ID` | the card gives the broker's reason; paste the token again, with the `token/` prefix |
| Reader feed connects and drops again and again, with a warning about `MQTT_CLIENT_ID` | another program uses the same client id | give this gateway its own (`Web_Edge_728589_<name>`) |
| The log shows `MQTT connected` and, about a quarter of a second later, `MQTT disconnected (rc=7)`, over and over, and the card says the broker hangs up right after the login | the login is accepted, then the broker closes the connection without answering the subscription. It does not like the client id, the subscription or its QoS. The cloud's own subscribers use an id ending in the device key, subscribe to `token/<API token>` and the topic together, and ask for QoS 2, which are this gateway's defaults now | stop the gateway, `set -a; . ./.env; set +a`, then `python drill/mqtt_probe.py`: it tries eight combinations against your broker and prints the settings that work. Also try `LOG_LEVEL=DEBUG`: it logs every MQTT packet (the token masked) |
| Reader feed **Connected** but "Last message: None yet" while readers are being tapped | wrong topic (company or device key), or the readers' own uplink is down so Safetrust publishes nothing | check `MQTT_TOPICS`; look at the Safetrust portal. USB swipes still work |
| A topic says "refused by the broker" | the topic is wrong or the account may not read it | correct `MQTT_TOPICS` |
| Cloud backend **Offline** | no Internet, DNS, firewall, a wrong `CLOUD_BASE_URL`, a proxy in the environment, or the backend is down | **Check connection**. A secure-handshake failure usually means the gateway's date is wrong |
| Cloud backend **Degraded** | the backend is up but its health check fails | look at the backend's database and logs; events are kept |
| Events under **Refused** in the store | the backend refuses them (for example HTTP 422) | open the row: the last error is there. They are kept and no longer retried |
| One tap makes two movements | the reader name after `=` in `LOCAL_INPUTS` is not the real wall reader, or the clocks differ by more than 10 s | fix the name; fix the clock (NTP); read `local.clockOffset` in `/status` |
| A typed number does nothing | `stdin`: the terminal is not in focus. `kbd`: no permission on the device. The text is not 1 to 12 digits (counted as rejected) | click the terminal; add the device and `--group-add input` in Docker; read `counters.localRejected` |
| `reader 'X' is not served by this gateway` | the name is not in `LOCAL_INPUTS` or `LOCAL_READERS` | add it |
| The gateway stops at start-up with a message about the credential map | a malformed entry, a repeated number, or one card for two numbers in `LOCAL_CREDENTIAL_MAP` | fix the list |
| `DeprecationWarning: Callback API version 1 is deprecated` at start | a notice from paho-mqtt 2.x | harmless |
| The console looks old after you edit `console.html` | the gateway caches the page | restart the gateway |
| `Address already in use` | something else uses `EDGE_BIND_PORT` | change it |

## 11. Daily use

* **Stop:** Ctrl-C, or `docker stop`. The store is on disk; nothing waiting is lost.
* **Start again:** the same command. Events still in the store are sent first.
* **Update:** copy the new files over `edge_gateway/`, restart. The store file is untouched.
* **Look inside the store.** `sqlite3 edge_outbox.db "select seq_id, status, attempts, substr(payload_json, 1, 90) from edge_queue"` (the `sqlite3` command is on macOS and most Linux). The store is a queue of what is **waiting** to be sent, not a history: the gateway deletes an event the moment the cloud confirms it, so with the backend Online the table is empty almost at once. To see a row, make the backend unreachable first (`CLOUD_BASE_URL=http://127.0.0.1:9/api/v1`, or stop the backend) and tap or type a number: it appears as `PENDING`, and goes when the backend returns. The recent rows can also sit in the `edge_outbox.db-wal` side file, so a file manager may show the `.db` itself as nearly empty; `sqlite3` reads both.
* **Reset the store** (it deletes the events still waiting): stop the gateway, delete `edge_outbox.db`, `edge_outbox.db-wal` and `edge_outbox.db-shm`.
* The console's activity history (what was sent, retried, refused, and the connection changes) lives in memory and starts empty after a restart. The store does not.
