# Testing WAN loss and lost MQTT feeds

## First, which link are you breaking?

There are three separate links. Each fails differently and each has a different safety net.

```
 Safetrust reader ──(1) reader → Safetrust cloud──►  Safetrust broker  ──(2) broker → gateway──►  edge gateway ──(3) gateway → backend──►  VinaBeef backend (AWS)
   (in the barn)         barn Internet / Wi-Fi         wss://sensor.demo.safetrust.com            MQTT over WAN                 queue on disk          HTTPS
```

| Link lost | What happens today | Events lost? |
|---|---|---|
| **(3) gateway → backend** (barn WAN down, AWS down, DB down) | The gateway keeps accepting events from MQTT, stores each one on disk, and replays them oldest hardware-time first when the backend is healthy again. | **No** (verified by the drill below) |
| **(2) broker → gateway** (the gateway lost its MQTT connection but the readers are still publishing to Safetrust) | The gateway reconnects and re-subscribes by itself. Events published while it was away are **not** replayed with the default clean session. | **Yes, unless the broker keeps them for you** (see test 4) |
| **(1) reader → Safetrust** (barn Internet down, reader offline) | Safetrust publishes nothing, so the gateway has nothing to queue. | **Depends on the reader's own buffering. Not provable from our side.** Ask Safetrust; test 5 shows what to look at. |

Link (3) is the only one the VinaBeef software can fully protect. Links (1) and (2) need either reader/broker buffering or a broker on the barn LAN.

## Test 1: automated drill (no hardware, 2 minutes)

`edge_gateway/drill/wan_drill.py` starts a local MQTT broker, a fake backend whose behaviour it switches, and the real `barn_edge_gateway.py`.

```bash
pip install amqtt paho-mqtt httpx
python edge_gateway/drill/wan_drill.py
```

It checks: normal flow; backend unreachable (OFFLINE, events queued, replayed in hardware-time order, no duplicates); backend up but database down (DEGRADED, backlog is not thrown at it); health OK but writes returning 503 (events kept, not marked DEAD); hanging backend (intake keeps working); `kill -9` of the gateway during an outage (queue survives on disk); the same EVENT_KEY delivered twice (sent once); broker lost and back (automatic reconnect and re-subscribe); and the documented loss of events published while the gateway was away with a clean session.

`edge_gateway/drill/usb_drill.py` does the same for the USB path (same packages; ports 18930/18180/18190). A stdin "USB reader" and `POST /local-swipe` play the reader. It checks: the reader's copy arrives during the hold (swipe becomes a shadow, only the reader's event is sent); no copy (swipe sent after the hold with its negative key, device and reader); the HTTP endpoint and what it refuses; text that is not a number (rejected and counted); backend down (swipes wait on disk, delivered once); the broker lost (a held swipe is released at once and new swipes are not held); `kill -9` while a swipe is held (it survives and is delivered once).

Expected last lines of `wan_drill.py`: `PASS I. clean session (default): events published while the gateway was away are LOST (documented limit)` and `ALL PASS`. The `INFO I. persistent session` line is informational; the local test broker does not queue for offline clients, so that behaviour has to be tested against Safetrust (test 4).

## Test 2: real edge gateway + real backend, cut the barn WAN (link 3)

Setup: backend running (local or AWS), gateway running with `CLOUD_BASE_URL` pointing at it and `MQTT_*` pointing at Safetrust, a Safetrust reader that you can swipe a registered tag on.

1. Baseline: `curl http://<gateway>:8090/status` shows `"wan":"ONLINE"`, `"mqttConnected":true`, `"queue":{"pending":0,...}`. Swipe a tag; the movement appears in the dashboard within seconds.
2. **Cut the uplink to the backend but leave MQTT alone.** Pick one:
   * **Linux gateway, block only the backend** (best simulation, MQTT keeps flowing):
     ```bash
     sudo iptables -I OUTPUT -d <backend-ip> -p tcp --dport 443 -j DROP     # silent drop = a real WAN failure (timeouts)
     # or: ... -j REJECT --reject-with tcp-reset                              # fast failure (connection refused)
     ```
   * **Docker:** `docker network disconnect <net> <backend-container>` for a local backend, or stop the backend container.
   * **Degraded link:** `sudo tc qdisc add dev eth0 root netem loss 40% delay 800ms` (undo with `sudo tc qdisc del dev eth0 root`). Expect slow retries, never a loss.
3. Swipe 5 to 10 tags (include an IN and an OUT at the gate, and one tag twice).
4. Watch `curl http://<gateway>:8090/status`: `wan` goes `OFFLINE`, `queue.pending` rises, `oldestPendingAgeSeconds` grows. Gateway log: `Backend link ONLINE -> OFFLINE ...`. The dashboard shows nothing new; that is expected.
5. **Optional power-cut test:** `kill -9` the gateway process and start it again. `pending` must be unchanged.
6. Restore (`iptables -D OUTPUT ...`). Within a backoff period (max 60 s) the log shows `Forwarded N events`, `pending` returns to 0 and `wan` is `ONLINE`.
7. **Verify on the backend side:**
   * Every swipe is present exactly once, with the **hardware** time of the swipe (not the replay time):
     ```sql
     SELECT event_key, observed_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS swiped_at, received_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS arrived_at, reader_name, card_uuid
     FROM rfid_read_events WHERE received_at > now() - interval '1 hour' ORDER BY observed_at;
     ```
     `swiped_at` is the reader's own time; `arrived_at` is when the backend got it. After a replay the two differ by the length of the outage.
   * Movements were created in the right order (IN before OUT), so the presence state is right: Herd page, the animal's Presence = In barn / At pasture as expected.
   * Nothing is stuck: `SELECT * FROM ingestion_dead_letters WHERE replayed_at IS NULL;` returns no new rows. If it does, use `POST /api/v1/ingestion/dead-letters/replay`.
   * Reports → "Gate passes" and the dashboard IN/OUT today include the replayed swipes.
8. **What can legitimately look odd after a long outage:** Anomaly alerts or "missing cattle" raised by the live checks while the data was missing; they come from the time the check ran, not from the swipe time.

Backend down for real (instead of blocking the network): `docker stop <backend>`, or make the database unreachable (`pg_ctl stop`) to test `DEGRADED`. The gateway probes `/api/v1/ingestion/health` (which checks the database) before it sends a backlog.

## Test 3: the backend's own MQTT connection (mode `direct-mqtt`)

If `vinabeef.ingestion.mode: direct-mqtt`, the **backend** subscribes to Safetrust (no gateway in between). Then a WAN or broker loss on the backend side is link (2) and nothing is queued on disk anywhere.

1. Block the backend's outbound 443 to `sensor.demo.safetrust.com` (security group/egress rule on EC2, or `iptables` on the server).
2. The backend log shows the connection lost; `GET /api/v1/ingestion/failover-status` shows `directMqttConnected: false`, a `disconnectReason` and `activeIngressRoute: PYTHON_ADAPTER`. The Paho client has automatic reconnect with a 30 s keep-alive, so unblocking is enough.
3. Swipe during the outage and again after it. Expect the outage swipes to be **missing** (see test 4 for the broker-side option).

Recommendation: run the **edge gateway** (or the Python adapter) for production so that link (3) is buffered; use `direct-mqtt` for demos.

## Test 4: link (2), the gateway is off the broker while readers keep publishing

Question to answer for Safetrust: *does your broker keep QoS 1 messages for a client with a persistent session (`clean_session=false`) while it is offline?*

1. Stop the gateway (`docker stop barn-edge`, or Ctrl+C).
2. Swipe 3 tags at a reader that is online.
3. Start the gateway with `MQTT_CLEAN_SESSION=false MQTT_QOS=1` and the **same** `MQTT_CLIENT_ID` as before (a persistent session needs the same client id and at least one earlier connection with `clean_session=false`).
4. If `GET /status` → `counters.received` rises by 3 shortly after connecting, the broker queues for you. If not, the 3 swipes are only in Safetrust's own event history.
5. Keep `MQTT_CLIENT_ID` unique per running copy; a second client with the same id kicks the first one off.

Whatever the answer, the gap can be **repaired afterwards** from Safetrust's side (their event history or export) by sending the events through the same path: `POST http://<gateway>:8090/` with the original JSON. The backend deduplicates on `EVENT_KEY`, so re-sending events it already has does no harm.

## Test 5: link (1), a reader that cannot reach the Safetrust cloud

This is outside the VinaBeef software, so what you test is the reader.

1. Take the reader's uplink away only (unplug its Ethernet/switch the Wi-Fi AP off). Do **not** touch the gateway or backend.
2. Swipe a registered tag. Note what the reader itself does: does the door/gate still open? Does it beep/flash differently?
3. Restore the uplink and wait a few minutes. Check in the Safetrust dashboard and then in `GET /status` (`counters.received`) whether the swipe from step 2 arrives **late with its original timestamp** (the reader buffers) or **never** (it does not).
4. Ask Safetrust in writing: offline buffering size and duration, whether events are sent in order with the original `TIMESTAMP`, and whether the reader can publish to a **broker on the barn LAN** (`MQTT_URL=mqtt://192.168.x.x:1883` is already supported by the gateway). A LAN broker is the real fix for barn Internet outages.

Until that is confirmed, treat a barn Internet outage as a period where the **gate passes are not recorded by the wall readers**. Use the Reporting page "Raw reader reads" and the Safetrust history to reconcile afterwards, or run USB readers (Test 6).

If the swipe from step 2 arrived late with its original `TIMESTAMP`, the reader buffers. The cloud then receives the same tap a second time if a USB reader also reported it: look in Raw reader reads, result "Second copy of a tap that came by both routes". That row is the cross-route matcher doing its job: one movement, the second read kept as evidence.

## Test 6: USB readers carry the crossing while the barn has no Internet

Needs a Safetrust USB IoT reader next to a wall reader, `LOCAL_INPUTS` set on the gateway, and the credential number already known to the cloud (see below). Do this once **with** a buffering reader and once without, if Test 5 is not settled.

1. Check `GET /status` → `local.inputs[].connected` is `true`, and note `local.clockOffset` while the barn is online (swipe a few tags first). If `medianMs` is more than about 3 seconds, raise `LOCAL_MATCH_SKEW_MS` and `INGESTION_CROSS_SOURCE_WINDOW_MS` well above it, or fix the reader's clock (`maxAbsMs` is huge while a buffered replay is in the last 50 reads; ignore it unless it stays large).
2. **Everything up:** swipe one tag at the wall reader and the USB reader. After about `LOCAL_HOLD_MS`, `local.shadow` has gone up by one and Raw reader reads shows one Safetrust read, **no** USB row. (If the gateway has no MQTT, the swipe is forwarded and the cloud marks it "second copy": also correct.)
3. **Cut the barn's Internet** (readers and gateway uplink). Swipe a tag at the inner and then the outer USB reader. `local.queued` goes up, `queue.pending` holds the two events, the gateway reports `OFFLINE`.
4. **Restore the Internet.** The queue empties; Raw reader reads shows two rows with a sky-blue **USB** badge, the device name and "credential N · seen with this card by Safetrust"; the passage appears with the right direction and **once**.
5. If the wall readers buffered, a few minutes later their late events arrive with the original time: they must show as "Second copy ...", the passage count must not change.
6. Swipe a tag whose number the cloud has never seen with a Safetrust reader: the USB read shows "Credential N · matches no card yet", the barn gets an alert, no movement. **Tap that tag once at a Safetrust wall reader** (the cloud learns the number from the pair it reports), then `POST /api/v1/ingestion/local/resolve`: the stored swipe is processed with its own original time.

**How a credential number becomes known.** The cloud trusts what Safetrust reported for that number (credential and card together in a real event), and falls back to the `credential_identifier` of the tag register. A number that two different cards were seen with is **never guessed**. Tapping each new tag once at a Safetrust reader while online registers it.

**What this does not give you:** the USB reader has no clock, so its times are the gateway's (check NTP); one USB reader per wall reader is needed for direction; the evdev/serial reading has not been tried on the real device by the author, so step 3 is the first real test of that code.

## What to watch while testing

| Where | What it tells you |
|---|---|
| `curl http://<gateway>:8090/status` | `wan` (ONLINE / DEGRADED / OFFLINE), `mqttConnected`, `queue.pending`, `queue.dead`, `oldestPendingAgeSeconds`, counters (`received`, `duplicates`, `forwarded`, `dead`) |
| Gateway log | `Backend link ... -> OFFLINE`, `MQTT disconnected (rc=...)`, `Forwarded N events` |
| `GET /api/v1/ingestion/health` | what the gateway probes: `ONLINE`, or `503 DEGRADED` when the database is down |
| `GET /api/v1/ingestion/failover-status` | direct-MQTT state (`directMqttConnected`, `disconnectReason`, `lastDirectEventAgeSeconds`) |
| `ingestion_dead_letters` | events the backend could not decode or store; replay with `/api/v1/ingestion/dead-letters/replay` |
| Reporting → Raw reader reads | every read the backend received, including ones that made no movement; filters "Read by a gateway USB reader", "Second copy of a tap that came by both routes", "USB read with an unknown credential number" |
| `curl http://<gateway>:8090/status` → `local` | `held` / `queued` / `shadow` USB swipes, whether each USB input is connected, `clockOffset` (size the matching window from it), `counters.localRejected` |

## Limits worth knowing

* The queue is capped at `EDGE_MAX_QUEUE_ROWS` (200,000). Past it the gateway drops telemetry first, then the oldest card reads, and logs an error. At barn traffic this is days, not hours.
* An event the backend keeps rejecting (HTTP 4xx other than 408/429) is retried `EDGE_MAX_ATTEMPTS` times and then marked `DEAD`; it stays on disk and shows in `/status`.
* Keep `EDGE_DB_PATH` on storage that survives a reboot (the Docker example uses a named volume).
* `/api/v1/ingestion/**` is open (`permitAll`) in `SecurityConfig`. Anyone who finds the URL can post events, **including events that claim to be USB swipes** (the `EDGE_*` fields are only protected at the gateway, which strips them from reader and LAN events). Put a shared secret in front of it before this is exposed beyond the demo.
* USB swipe times are the gateway's clock. A gateway whose clock is wrong by more than the matching window puts its USB swipes outside the window of the Safetrust copies, and the same tap is then counted twice.
* The edge matches a USB swipe only with copies that reach this gateway over MQTT. If the gateway has no MQTT, or its MQTT is down, every USB swipe is forwarded and the cloud matcher alone removes the duplicates.
