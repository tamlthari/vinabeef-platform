#!/usr/bin/env python3
"""
Barn edge gateway (store-and-forward) for VinaBeef.

Reader events reach the gateway two ways and land in ONE durable queue (SQLite, WAL, synchronous=FULL):

  1. MQTT   - subscribes to the reader events (Safetrust cloud broker, or a broker on the barn LAN).
  2. HTTP   - POST /  with one event or a list (LAN devices, test tools, the USB reader bridge).

A background worker forwards the queue to the VinaBeef backend in hardware-time order and deletes an event
ONLY when the backend confirms it (HTTP 200, or an OK entry in the 207 result list). If the backend or the WAN
is down, events stay in the queue and are retried with exponential backoff. The backend is idempotent on
EVENT_KEY, so resending is always safe.

Configuration is by environment variables (see edge.env.example). Secrets are never hard-coded.
"""
import base64
import binascii
import hashlib
import json
import logging
import os
import random
import re
import signal
import sqlite3
import ssl
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import httpx

log = logging.getLogger("edge")

# camelCase names accepted from the earlier HTTP interface -> Safetrust names the backend reads
ALIASES = {
    "eventKey": "EVENT_KEY", "timestamp": "TIMESTAMP", "cardUuid": "CARD_UUID", "readerName": "READER_NAME",
    "companyKey": "COMPANY_KEY", "deviceKey": "DEVICE_KEY", "transactionId": "TRANSACTION_ID",
}


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    return default if value is None else value.strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Config:
    db_path: str = "edge_outbox.db"
    cloud_base_url: str = "http://localhost:8080/api/v1"
    batch_size: int = 100
    max_attempts: int = 20            # an event the backend keeps rejecting becomes DEAD (kept, not retried)
    max_queue_rows: int = 200_000     # safety cap for very long outages; telemetry is dropped first
    bind_host: str = "0.0.0.0"
    bind_port: int = 8090
    ingest_token: str = ""            # optional shared secret for the LAN HTTP endpoint (header X-Edge-Token)
    backoff_min: float = 2.0
    backoff_max: float = 60.0
    # MQTT source (leave mqtt_url empty to run HTTP-only)
    mqtt_url: str = ""
    mqtt_username: str = ""
    mqtt_password: str = ""
    mqtt_topics: str = ""             # comma separated, e.g. Web/475/Monitoring/728589
    mqtt_client_id: str = "Web_Edge_gateway"
    mqtt_clean_session: bool = True
    mqtt_qos: int = 1
    mqtt_tls_insecure: bool = True    # the Safetrust demo broker needs it; set false with a real CA
    mqtt_ca_certs: str = ""

    @classmethod
    def from_env(cls) -> "Config":
        g = os.getenv
        return cls(
            db_path=g("EDGE_DB_PATH", cls.db_path),
            cloud_base_url=g("CLOUD_BASE_URL", cls.cloud_base_url).rstrip("/"),
            batch_size=int(g("EDGE_BATCH_SIZE", cls.batch_size)),
            max_attempts=int(g("EDGE_MAX_ATTEMPTS", cls.max_attempts)),
            max_queue_rows=int(g("EDGE_MAX_QUEUE_ROWS", cls.max_queue_rows)),
            bind_host=g("EDGE_BIND_HOST", cls.bind_host),
            bind_port=int(g("EDGE_BIND_PORT", cls.bind_port)),
            ingest_token=g("EDGE_INGEST_TOKEN", ""),
            backoff_min=float(g("EDGE_BACKOFF_MIN", cls.backoff_min)),
            backoff_max=float(g("EDGE_BACKOFF_MAX", cls.backoff_max)),
            mqtt_url=g("MQTT_URL", ""),
            mqtt_username=g("MQTT_USERNAME", ""),
            mqtt_password=g("MQTT_PASSWORD", ""),
            mqtt_topics=g("MQTT_TOPICS", ""),
            mqtt_client_id=g("MQTT_CLIENT_ID", cls.mqtt_client_id),
            mqtt_clean_session=_env_bool("MQTT_CLEAN_SESSION", True),
            mqtt_qos=int(g("MQTT_QOS", cls.mqtt_qos)),
            mqtt_tls_insecure=_env_bool("MQTT_TLS_INSECURE", True),
            mqtt_ca_certs=g("MQTT_CA_CERTS", ""),
        )


# --------------------------------------------------------------------------------------------------------
# Decoding and normalisation
# --------------------------------------------------------------------------------------------------------
def decode_payload(raw: bytes):
    """Returns (event_dict, json_text) or None. Accepts plain JSON, base64 and URL-safe base64 JSON."""
    text = raw.decode("utf-8", "replace").strip()
    if not (text.startswith("{") and text.endswith("}")):
        if len(text) > 1 and text[0] == '"' and text[-1] == '"':
            text = text[1:-1].strip()
        b64 = re.sub(r"\s+", "", text).replace("-", "+").replace("_", "/")
        b64 += "=" * (-len(b64) % 4)
        try:
            text = base64.b64decode(b64).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError, ValueError):
            return None
    try:
        obj = json.loads(text)
    except ValueError:
        return None
    return (obj, text) if isinstance(obj, dict) else None


def normalise(event: dict, raw_json: str):
    """Safetrust field names, a stable EVENT_KEY, hardware TIMESTAMP and the untouched payload as rawPayload."""
    ev = dict(event)
    for alias, name in ALIASES.items():
        if alias in ev and name not in ev:
            ev[name] = ev.pop(alias)
    if ev.get("CARD_UUID"):
        ev["CARD_UUID"] = str(ev["CARD_UUID"]).strip().upper()
    if not isinstance(ev.get("EVENT_KEY"), int):
        # No key from the source: derive one from the content so a resend of the same event is a duplicate.
        ev["EVENT_KEY"] = int(hashlib.sha1(raw_json.encode("utf-8")).hexdigest()[:15], 16)
    if not isinstance(ev.get("TIMESTAMP"), int):
        ev["TIMESTAMP"] = int(time.time() * 1000)
    ev["rawPayload"] = raw_json
    return ev


# --------------------------------------------------------------------------------------------------------
# Durable queue
# --------------------------------------------------------------------------------------------------------
class Outbox:
    def __init__(self, path: str, max_rows: int):
        self.max_rows = max_rows
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL;")
        self.db.execute("PRAGMA synchronous=FULL;")   # an acknowledged event survives a power cut
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS edge_queue (
                seq_id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_key INTEGER UNIQUE,
                hardware_ts INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING'
            );""")
        have = {r[1] for r in self.db.execute("PRAGMA table_info(edge_queue)")}
        for name, ddl in (("attempts", "INTEGER NOT NULL DEFAULT 0"), ("last_error", "TEXT"),
                          ("is_card", "INTEGER NOT NULL DEFAULT 1"), ("created_at", "INTEGER NOT NULL DEFAULT 0")):
            if name not in have:
                self.db.execute(f"ALTER TABLE edge_queue ADD COLUMN {name} {ddl}")
        self.db.execute("CREATE INDEX IF NOT EXISTS idx_edge_status_ts ON edge_queue(status, hardware_ts ASC, seq_id ASC);")
        self.db.commit()
        self._inserts = 0

    def add(self, event: dict) -> bool:
        """Stores the event durably. Returns False when the same EVENT_KEY is already queued."""
        with self.lock:
            cur = self.db.execute(
                "INSERT OR IGNORE INTO edge_queue (event_key, hardware_ts, payload_json, status, is_card, created_at) "
                "VALUES (?, ?, ?, 'PENDING', ?, ?)",
                (event["EVENT_KEY"], event["TIMESTAMP"], json.dumps(event), 1 if event.get("CARD_UUID") else 0,
                 int(time.time() * 1000)))
            self.db.commit()
            self._inserts += 1
            if self._inserts % 500 == 0:
                self._prune()
            return cur.rowcount == 1

    def _prune(self):
        n = self.db.execute("SELECT COUNT(*) FROM edge_queue").fetchone()[0]
        excess = n - self.max_rows
        if excess <= 0:
            return
        self.db.execute("DELETE FROM edge_queue WHERE seq_id IN (SELECT seq_id FROM edge_queue WHERE is_card = 0 "
                        "ORDER BY hardware_ts LIMIT ?)", (excess,))
        left = self.db.execute("SELECT COUNT(*) FROM edge_queue").fetchone()[0] - self.max_rows
        if left > 0:
            self.db.execute("DELETE FROM edge_queue WHERE seq_id IN (SELECT seq_id FROM edge_queue "
                            "ORDER BY hardware_ts LIMIT ?)", (left,))
            log.error("Queue over %d rows: dropped %d OLDEST CARD READS. Restore connectivity.", self.max_rows, left)
        self.db.commit()

    def next_batch(self, limit: int):
        with self.lock:
            return self.db.execute(
                "SELECT seq_id, event_key, payload_json FROM edge_queue WHERE status = 'PENDING' "
                "ORDER BY hardware_ts ASC, seq_id ASC LIMIT ?", (limit,)).fetchall()

    def delete(self, seq_ids):
        if not seq_ids:
            return
        with self.lock:
            self.db.executemany("DELETE FROM edge_queue WHERE seq_id = ?", [(s,) for s in seq_ids])
            self.db.commit()

    def fail(self, seq_id: int, error: str, max_attempts: int) -> bool:
        """Counts a failed attempt. Returns True when the event became DEAD."""
        with self.lock:
            self.db.execute("UPDATE edge_queue SET attempts = attempts + 1, last_error = ? WHERE seq_id = ?",
                            (error[:500], seq_id))
            cur = self.db.execute("UPDATE edge_queue SET status = 'DEAD' WHERE seq_id = ? AND attempts >= ?",
                                  (seq_id, max_attempts))
            self.db.commit()
            return cur.rowcount == 1

    def stats(self) -> dict:
        with self.lock:
            rows = dict(self.db.execute("SELECT status, COUNT(*) FROM edge_queue GROUP BY status").fetchall())
            oldest = self.db.execute("SELECT MIN(hardware_ts) FROM edge_queue WHERE status = 'PENDING'").fetchone()[0]
        return {
            "pending": rows.get("PENDING", 0), "dead": rows.get("DEAD", 0),
            "oldestPendingAgeSeconds": int(time.time() - oldest / 1000) if oldest else 0,
        }


# --------------------------------------------------------------------------------------------------------
# Gateway
# --------------------------------------------------------------------------------------------------------
class Gateway:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.outbox = Outbox(cfg.db_path, cfg.max_queue_rows)
        self.stop = threading.Event()
        self.wan_state = "UNKNOWN"          # ONLINE | DEGRADED (backend answered but cannot write) | OFFLINE
        self.last_success_at = None
        self.mqtt_connected = False
        self.counters = {"received": 0, "duplicates": 0, "ignored": 0, "forwarded": 0, "dead": 0}

    # ---- intake -------------------------------------------------------------------------------------
    def accept_event(self, event: dict, raw_json: str) -> bool:
        ev = normalise(event, raw_json)
        is_new = self.outbox.add(ev)
        self.counters["received" if is_new else "duplicates"] += 1
        return is_new

    def handle_mqtt_message(self, topic: str, payload: bytes):
        decoded = decode_payload(payload)
        if decoded is None or "EVENT_KEY" not in decoded[0]:
            self.counters["ignored"] += 1          # e.g. the token notice topic
            log.debug("Ignored non-event message on %s", topic)
            return
        self.accept_event(*decoded)

    # ---- forwarding ---------------------------------------------------------------------------------
    def forward_once(self, client: httpx.Client) -> str:
        """One cycle. Returns 'IDLE', 'SENT' or 'RETRY' (caller backs off)."""
        try:
            health = client.get(f"{self.cfg.cloud_base_url}/ingestion/health")
        except httpx.HTTPError as ex:
            self._wan("OFFLINE", f"backend unreachable: {ex.__class__.__name__}")
            return "RETRY"
        if health.status_code != 200:
            self._wan("DEGRADED", f"health returned {health.status_code}")
            return "RETRY"

        rows = self.outbox.next_batch(self.cfg.batch_size)
        if not rows:
            self._wan("ONLINE")
            return "IDLE"
        try:
            sent = self._post(client, rows)
        except httpx.HTTPError as ex:
            self._wan("OFFLINE", f"post failed: {ex.__class__.__name__}")
            return "RETRY"
        self._wan("ONLINE" if sent else "DEGRADED")
        return "SENT" if sent else "RETRY"

    def _post(self, client: httpx.Client, rows) -> bool:
        resp = client.post(f"{self.cfg.cloud_base_url}/ingestion/batch", json=[json.loads(r[2]) for r in rows])
        if resp.status_code == 200:
            self.outbox.delete([r[0] for r in rows])
            self._done(len(rows))
            return True
        if resp.status_code == 207:
            return self._apply_results(rows, resp.json().get("results", []))
        if 400 <= resp.status_code < 500 and resp.status_code not in (408, 429):
            if len(rows) > 1:                       # one bad event can make the whole list unreadable: find it
                return all([self._post(client, [r]) for r in rows])
            self._reject(rows[0], f"HTTP {resp.status_code}")
            return False
        log.warning("Backend answered %s, keeping %d events.", resp.status_code, len(rows))
        return False

    def _apply_results(self, rows, results) -> bool:
        status_by_key = {r.get("eventKey"): r for r in results}
        ok, all_ok = [], True
        for seq_id, event_key, _ in rows:
            result = status_by_key.get(event_key)
            if result is not None and result.get("status") == "OK":
                ok.append(seq_id)
            else:
                all_ok = False
                self._reject((seq_id, event_key, None), (result or {}).get("error", "missing from result"))
        self.outbox.delete(ok)
        self._done(len(ok))
        return all_ok

    def _reject(self, row, error: str):
        if self.outbox.fail(row[0], error, self.cfg.max_attempts):
            self.counters["dead"] += 1
            log.error("Event %s marked DEAD after %d attempts: %s", row[1], self.cfg.max_attempts, error)

    def _done(self, n: int):
        self.counters["forwarded"] += n
        self.last_success_at = time.time()
        log.info("Forwarded %d events to the backend.", n)

    def _wan(self, state: str, why: str = ""):
        if state == self.wan_state:
            return
        if state == "ONLINE":
            log.info("Backend link %s -> ONLINE", self.wan_state)
        else:
            log.warning("Backend link %s -> %s %s", self.wan_state, state, why)
        self.wan_state = state

    def forward_loop(self):
        delay = self.cfg.backoff_min
        with httpx.Client(timeout=10.0) as client:
            while not self.stop.is_set():
                try:
                    result = self.forward_once(client)
                except Exception:                      # never let the worker die
                    log.exception("Forwarder error")
                    result = "RETRY"
                if result == "SENT":
                    delay, wait = self.cfg.backoff_min, 0.05
                elif result == "IDLE":
                    delay, wait = self.cfg.backoff_min, 1.0
                else:
                    wait = delay * random.uniform(0.8, 1.2)
                    delay = min(self.cfg.backoff_max, delay * 2)
                self.stop.wait(wait)

    def status(self) -> dict:
        return {
            "wan": self.wan_state, "mqttConnected": self.mqtt_connected, "queue": self.outbox.stats(),
            "lastForwardedAgeSeconds": int(time.time() - self.last_success_at) if self.last_success_at else None,
            "counters": dict(self.counters),
        }

    # ---- MQTT source --------------------------------------------------------------------------------
    def start_mqtt(self):
        if not self.cfg.mqtt_url:
            log.info("MQTT_URL not set: HTTP intake only.")
            return None
        import paho.mqtt.client as mqtt
        cfg = self.cfg
        url = urlparse(cfg.mqtt_url)
        websockets = url.scheme in ("ws", "wss")
        tls = url.scheme in ("wss", "mqtts", "ssl")
        port = url.port or (443 if url.scheme == "wss" else 80 if url.scheme == "ws" else 8883 if tls else 1883)
        kwargs = dict(client_id=cfg.mqtt_client_id, clean_session=cfg.mqtt_clean_session,
                      transport="websockets" if websockets else "tcp")
        try:                                           # paho-mqtt 2.x
            client = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION1, **kwargs)
        except AttributeError:                         # paho-mqtt 1.x
            client = mqtt.Client(**kwargs)
        if websockets:
            client.ws_set_options(path=url.path or "/mqtt")
        if tls:
            if cfg.mqtt_tls_insecure:
                log.warning("MQTT TLS certificate checks are OFF (MQTT_TLS_INSECURE).")
                client.tls_set(cert_reqs=ssl.CERT_NONE)
                client.tls_insecure_set(True)
            else:
                client.tls_set(ca_certs=cfg.mqtt_ca_certs or None)
        if cfg.mqtt_username:
            client.username_pw_set(cfg.mqtt_username, cfg.mqtt_password)
        topics = [t.strip() for t in cfg.mqtt_topics.split(",") if t.strip()]

        def on_connect(c, userdata, flags, rc):
            if rc != 0:
                self.mqtt_connected = False
                log.error("MQTT connect refused (rc=%s)", rc)
                return
            self.mqtt_connected = True
            log.info("MQTT connected to %s; subscribing to %s", cfg.mqtt_url, topics)
            for t in topics:                           # on EVERY (re)connect: a clean session forgets them
                c.subscribe(t, qos=cfg.mqtt_qos)

        def on_subscribe(c, userdata, mid, granted):
            log.info("MQTT subscription granted QoS %s", list(granted))
            if 128 in granted and cfg.mqtt_qos != 0:   # the broker refused this QoS: fall back to 0
                for t in topics:
                    c.subscribe(t, qos=0)

        def on_disconnect(c, userdata, rc):
            self.mqtt_connected = False
            log.warning("MQTT disconnected (rc=%s); the client reconnects on its own.", rc)

        def on_message(c, userdata, msg):
            try:
                self.handle_mqtt_message(msg.topic, msg.payload)
            except Exception:
                log.exception("Could not queue a message from %s", msg.topic)

        client.on_connect, client.on_subscribe = on_connect, on_subscribe
        client.on_disconnect, client.on_message = on_disconnect, on_message
        client.reconnect_delay_set(min_delay=1, max_delay=60)
        client.connect_async(url.hostname, port, keepalive=30)   # keeps retrying even if the first attempt fails
        client.loop_start()
        return client

    # ---- HTTP intake --------------------------------------------------------------------------------
    def http_server(self) -> ThreadingHTTPServer:
        gw = self

        class Handler(BaseHTTPRequestHandler):
            def _json(self, code: int, body: dict):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.path.startswith("/status"):
                    self._json(200, gw.status())
                elif self.path.startswith("/health"):
                    self._json(200, {"status": "UP"})
                else:
                    self._json(404, {"error": "not found"})

            def do_POST(self):
                if gw.cfg.ingest_token and self.headers.get("X-Edge-Token") != gw.cfg.ingest_token:
                    return self._json(401, {"error": "bad token"})
                try:
                    raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    body = json.loads(raw)
                    events = body if isinstance(body, list) else [body]
                    if not all(isinstance(e, dict) for e in events):
                        raise ValueError("expected an event object or a list of event objects")
                    queued = sum(1 for e in events if gw.accept_event(e, json.dumps(e)))
                except Exception as ex:
                    return self._json(400, {"error": str(ex)})
                self._json(202, {"status": "BUFFERED_IN_EDGE_WAL", "queued": queued, "duplicates": len(events) - queued})

            def log_message(self, fmt, *args):
                return

        return ThreadingHTTPServer((self.cfg.bind_host, self.cfg.bind_port), Handler)


def main():
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    gw = Gateway(Config.from_env())
    threading.Thread(target=gw.forward_loop, name="forwarder", daemon=True).start()
    mqtt_client = gw.start_mqtt()
    server = gw.http_server()
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown).start())
    log.info("Barn edge gateway up: HTTP %s:%s, queue %s, backend %s",
             gw.cfg.bind_host, gw.cfg.bind_port, gw.cfg.db_path, gw.cfg.cloud_base_url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        gw.stop.set()
        if mqtt_client:
            mqtt_client.loop_stop()
            mqtt_client.disconnect()


if __name__ == "__main__":
    main()
