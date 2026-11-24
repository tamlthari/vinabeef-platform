#!/usr/bin/env python3
"""
Barn edge gateway (store-and-forward) for VinaBeef.

Reader events reach the gateway three ways and land in ONE durable queue (SQLite, WAL, synchronous=FULL):

  1. MQTT   - subscribes to the reader events (Safetrust cloud broker, or a broker on the barn LAN).
  2. HTTP   - POST /  with one event or a list (LAN devices, test tools).
  3. USB    - Safetrust USB IoT readers on this machine (keyboard, serial) or POST /local-swipe. They send only a
              credential number, no time: the gateway stamps the time, gives the swipe a negative EVENT_KEY and,
              while MQTT is up, holds it briefly to see whether the reader's own copy of the tap shows up.
              See local_swipes.py and usb_input.py.

A background worker forwards the queue to the VinaBeef backend in hardware-time order and deletes an event
ONLY when the backend confirms it (HTTP 200, or an OK entry in the 207 result list). If the backend or the WAN
is down, events stay in the queue and are retried with exponential backoff. The backend is idempotent on
EVENT_KEY, so resending is always safe.

Configuration is by environment variables (see edge.environment.example and RUN-AND-TEST.md). Secrets are never hard-coded.
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

from edge_console import TOKEN_NOTICES, ConsoleApi, SyncJournal, clean_broker_url, connack_reason, disconnect_reason
from local_swipes import LocalSwipes
from usb_input import build_source, parse_credential, parse_credential_map, parse_inputs

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
    console_token: str = ""           # optional password for the read-only web console (/api/*, header X-Edge-Token)
    backoff_min: float = 2.0
    backoff_max: float = 60.0
    # MQTT source (leave mqtt_url empty to run HTTP-only)
    mqtt_url: str = ""
    mqtt_username: str = ""
    mqtt_password: str = ""
    mqtt_topics: str = ""             # comma separated, e.g. Web/475/Monitoring/728589
    mqtt_client_id: str = ""          # blank: Web_Edge_<MQTT_USERNAME>. Safetrust's own subscribers are Web_<role>_<device key>[_<suffix>]
    mqtt_clean_session: bool = True
    mqtt_qos: int = 2                 # what the backend and the standby adapter ask Safetrust for
    mqtt_token_topic: str = "auto"    # auto | true | false: also subscribe to the password ("token/<API token>"), as they do
    mqtt_tls_insecure: bool = True    # the Safetrust demo broker needs it; set false with a real CA
    mqtt_ca_certs: str = ""
    # Local USB readers (leave local_inputs empty to run without them; POST /local-swipe still works for readers in local_readers)
    gateway_no: int = 1               # 1..99, unique per gateway: namespaces the negative EVENT_KEYs of its USB swipes
    local_inputs: str = ""            # kbd:/dev/input/event5=DrC0F8,serial:/dev/ttyACM0@9600=DrC1C4,stdin=DrC0F8
    local_readers: str = ""           # extra Safetrust reader names POST /local-swipe may use (inputs add their own)
    local_hold_ms: int = 6000         # how long a swipe waits for the reader's own copy while MQTT is up (0 = never wait)
    local_match_skew_ms: int = 10_000  # two reads are the same tap when closer than this
    local_idle_flush_ms: int = 200    # a USB device that sends no Enter ends a swipe after this silence
    local_retention_s: int = 86_400   # how long the swipe log is kept
    local_company_key: int = 0        # optional COMPANY_KEY copied onto USB swipes (0 = none)
    local_credential_map: str = ""    # 36=042FAC02AD1490,38=044E6602AD1490: the card UUID of a typed number (demo, or numbers the cloud does not know)
    local_grab_keyboard: bool = True  # take the keyboard device exclusively so digits do not reach a console

    def client_id(self) -> str:
        """The MQTT client id. Safetrust's own subscribers are Web_Direct_<device key>[_<suffix>] (the backend) and
        Web_Standby_<device key> (the standby adapter): the 'Web_' prefix, then the device key. Blank means the same
        shape, Web_Edge_<MQTT_USERNAME>; two gateways on one account need a suffix each (MQTT_CLIENT_ID=Web_Edge_728589_barn1)."""
        if self.mqtt_client_id:
            return self.mqtt_client_id
        return f"Web_Edge_{self.mqtt_username}" if self.mqtt_username else "Web_Edge_gateway"

    def token_topic(self) -> str:
        """The topic named by the password, or "". The backend and the standby adapter subscribe to 'token/<API token>'
        next to the monitoring topic, and the broker sends token notices there. With auto, a password that starts with
        'token/' is used; true uses any non-empty password; false never subscribes to it."""
        mode = (self.mqtt_token_topic or "auto").strip().lower()
        if mode in ("0", "false", "no", "off") or not self.mqtt_password:
            return ""
        if mode in ("1", "true", "yes", "on"):
            return self.mqtt_password
        return self.mqtt_password if self.mqtt_password.startswith("token/") else ""

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
            console_token=g("EDGE_CONSOLE_TOKEN", ""),
            backoff_min=float(g("EDGE_BACKOFF_MIN", cls.backoff_min)),
            backoff_max=float(g("EDGE_BACKOFF_MAX", cls.backoff_max)),
            mqtt_url=g("MQTT_URL", ""),
            mqtt_username=g("MQTT_USERNAME", ""),
            mqtt_password=g("MQTT_PASSWORD", ""),
            mqtt_topics=g("MQTT_TOPICS", ""),
            mqtt_client_id=g("MQTT_CLIENT_ID", ""),
            mqtt_clean_session=_env_bool("MQTT_CLEAN_SESSION", True),
            mqtt_qos=int(g("MQTT_QOS", cls.mqtt_qos)),
            mqtt_token_topic=g("MQTT_TOKEN_TOPIC", cls.mqtt_token_topic),
            mqtt_tls_insecure=_env_bool("MQTT_TLS_INSECURE", True),
            mqtt_ca_certs=g("MQTT_CA_CERTS", ""),
            gateway_no=int(g("EDGE_GATEWAY_NO", cls.gateway_no)),
            local_inputs=g("LOCAL_INPUTS", ""),
            local_readers=g("LOCAL_READERS", ""),
            local_hold_ms=int(g("LOCAL_HOLD_MS", cls.local_hold_ms)),
            local_match_skew_ms=int(g("LOCAL_MATCH_SKEW_MS", cls.local_match_skew_ms)),
            local_idle_flush_ms=int(g("LOCAL_IDLE_FLUSH_MS", cls.local_idle_flush_ms)),
            local_retention_s=int(g("LOCAL_LOG_RETENTION_S", cls.local_retention_s)),
            local_company_key=int(g("LOCAL_COMPANY_KEY", cls.local_company_key)),
            local_credential_map=g("LOCAL_CREDENTIAL_MAP", ""),
            local_grab_keyboard=_env_bool("LOCAL_GRAB_KEYBOARD", True),
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
                (event["EVENT_KEY"], event["TIMESTAMP"], json.dumps(event),
                 1 if (event.get("CARD_UUID") or event.get("CREDENTIAL_IDENTIFIER")) else 0,
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
            "pending": rows.get("PENDING", 0), "dead": rows.get("DEAD", 0), "held": rows.get("HELD", 0),
            "oldestPendingAgeSeconds": int(time.time() - oldest / 1000) if oldest else 0,
        }


# --------------------------------------------------------------------------------------------------------
# Gateway
# --------------------------------------------------------------------------------------------------------
class _Redact(logging.Filter):
    """Hides a secret in log lines. paho's debug log prints SUBSCRIBE packets, and the token topic is the token."""

    def __init__(self, secret: str):
        super().__init__()
        self.secret = secret

    def filter(self, record):
        if self.secret:
            text = record.getMessage()
            if self.secret in text:
                record.msg, record.args = text.replace(self.secret, "token/***"), ()
        return True


def build_mqtt_client(cfg: "Config", client_id: str = None):
    """A paho client set up like the cloud's own Safetrust subscribers: websocket path from the URL, TLS without certificate
    checks for the demo broker, user name and password. Returns (client, host, port); the caller connects it."""
    import paho.mqtt.client as mqtt
    url = urlparse(cfg.mqtt_url)
    websockets = url.scheme in ("ws", "wss")
    tls = url.scheme in ("wss", "mqtts", "ssl")
    port = url.port or (443 if url.scheme == "wss" else 80 if url.scheme == "ws" else 8883 if tls else 1883)
    kwargs = dict(client_id=client_id or cfg.client_id(), clean_session=cfg.mqtt_clean_session,
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
    return client, url.hostname, port


class Gateway:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.outbox = Outbox(cfg.db_path, cfg.max_queue_rows)
        self.stop = threading.Event()
        self.wan_state = "UNKNOWN"          # ONLINE | DEGRADED (backend answered but cannot write) | OFFLINE
        self.last_success_at = None
        self.mqtt_connected = False
        self.counters = {"received": 0, "duplicates": 0, "ignored": 0, "forwarded": 0, "dead": 0,
                         "localReceived": 0, "localHeld": 0, "localShadow": 0, "localQueued": 0, "localRejected": 0}
        self.credential_cards = parse_credential_map(cfg.local_credential_map)
        self.local = LocalSwipes(self.outbox, cfg.gateway_no, cfg.local_hold_ms, cfg.local_match_skew_ms,
                                 cfg.local_retention_s, cfg.local_company_key, cards=self.credential_cards)
        self.local_specs = parse_inputs(cfg.local_inputs)
        self.local_readers = {r.strip() for r in cfg.local_readers.split(",") if r.strip()} | {s.reader for s in self.local_specs}
        self.local_sources = []
        self.journal = SyncJournal()        # what the web console shows about the link and the sync; in memory only
        self.console = ConsoleApi(self)
        self._post_ms = None
        self._health_at = None
        self._sub_mids = {}                 # subscribe request id -> [(topic, qos)], to match each broker answer to its topics
        if cfg.mqtt_url:
            self.journal.mqtt_setup(self.mqtt_display_topics())

    # ---- intake -------------------------------------------------------------------------------------
    def accept_event(self, event: dict, raw_json: str) -> bool:
        # EDGE_* fields are written by this gateway for its own USB swipes and nowhere else: a reader event or a LAN
        # POST must not be able to pose as one.
        event = {k: v for k, v in event.items() if not str(k).startswith("EDGE_")}
        ev = normalise(event, raw_json)
        is_new = self.outbox.add(ev)
        self.counters["received" if is_new else "duplicates"] += 1
        if is_new:
            self.journal.received()
            try:                                   # matching is an optimisation: it must never lose the event
                self.local.record_safetrust(ev)
            except Exception:
                log.exception("Could not record read %s for USB matching", ev.get("EVENT_KEY"))
        return is_new

    # ---- local USB swipes ---------------------------------------------------------------------------
    def accept_local(self, reader: str, device: str, text, t_ms: int = None):
        """One swipe of a USB reader. Raises ValueError (and counts it) when the text is not a credential number or the
        reader is not one this gateway serves. Returns a LocalResult: HELD, QUEUED or SHADOW."""
        credential = parse_credential(text)
        if credential is None:
            self.counters["localRejected"] += 1
            raise ValueError(f"'{str(text)[:40]}' is not a credential number (1 to 12 digits)")
        if reader not in self.local_readers:
            self.counters["localRejected"] += 1
            raise ValueError(f"reader '{reader}' is not served by this gateway (LOCAL_INPUTS / LOCAL_READERS)")
        t_ms = int(time.time() * 1000) if t_ms is None else t_ms
        # Wait for the reader's own copy only while MQTT is connected: with MQTT down no copy can reach this gateway.
        hold = self.mqtt_connected and self.cfg.local_hold_ms > 0
        result = self.local.submit(reader, device, credential, str(text), t_ms, hold)
        self.counters["localReceived"] += 1
        self.counters[{"HELD": "localHeld", "SHADOW": "localShadow", "QUEUED": "localQueued"}[result.state]] += 1
        if result.state == "QUEUED":                # a HELD swipe counts when its hold ends; a SHADOW never does
            self.journal.received()
        log.info("USB swipe: credential %s at reader %s from %s -> %s", credential, reader, device, result.state)
        return result

    def _local_sink(self, reader: str, device: str, text: str, t_ms: int):
        try:
            self.accept_local(reader, device, text, t_ms)
        except ValueError as ex:
            log.warning("USB input from %s rejected: %s", device, ex)

    def start_local_inputs(self):
        for spec in self.local_specs:
            src = build_source(spec, self._local_sink, self.cfg.local_idle_flush_ms, self.cfg.local_grab_keyboard)
            src.start()
            self.local_sources.append(src)
        if self.local_specs:
            log.info("USB inputs: %s", ", ".join(f"{s.device}->{s.reader}" for s in self.local_specs))
        else:
            log.info("USB inputs: none. LOCAL_INPUTS is empty, so nothing listens for a USB reader or typed numbers "
                     "(set it, for example stdin=F9E2).")

    def local_loop(self):
        """Ends holds that ran out, and trims the swipe log."""
        last_prune = 0.0
        while not self.stop.wait(0.25):
            try:
                released = self.local.release_due()
                if released:
                    self.journal.received(released)
                    log.info("USB swipes with no reader copy within %d ms were queued.", self.cfg.local_hold_ms)
                if time.time() - last_prune > 60:
                    self.local.prune()
                    last_prune = time.time()
            except Exception:
                log.exception("USB hold loop error")

    def handle_mqtt_message(self, topic: str, payload: bytes):
        decoded = decode_payload(payload)
        is_event = decoded is not None and "EVENT_KEY" in decoded[0]
        self.journal.mqtt_message(is_event)
        if not is_event:
            self.counters["ignored"] += 1          # e.g. the token notice topic
            log.debug("Ignored non-event message on %s", topic)
            return
        self.accept_event(*decoded)

    # ---- forwarding ---------------------------------------------------------------------------------
    def forward_once(self, client: httpx.Client) -> str:
        """One cycle. Returns 'IDLE', 'SENT' or 'RETRY' (caller backs off)."""
        started = time.monotonic()
        try:
            health = client.get(f"{self.cfg.cloud_base_url}/ingestion/health")
        except httpx.HTTPError as ex:
            self.journal.probe(False, None, self._ms(started), ex.__class__.__name__)
            self._wan("OFFLINE", f"backend unreachable: {ex.__class__.__name__}")
            self.journal.retry(f"The cloud cannot be reached ({ex.__class__.__name__}). Events stay in the store.")
            return "RETRY"
        if health.status_code != 200:
            self.journal.probe(False, health.status_code, self._ms(started), f"HTTP {health.status_code}")
            self._wan("DEGRADED", f"health returned {health.status_code}")
            self.journal.retry(f"The cloud answered HTTP {health.status_code} to the health check. Events stay in the store.")
            return "RETRY"
        self.journal.probe(True, 200, self._ms(started))
        self._health_at = time.time()

        rows = self.outbox.next_batch(self.cfg.batch_size)
        if not rows:
            self._wan("ONLINE")
            self.journal.healthy()
            return "IDLE"
        try:
            sent = self._post(client, rows)
        except httpx.HTTPError as ex:
            self._wan("OFFLINE", f"post failed: {ex.__class__.__name__}")
            self.journal.retry(f"Sending failed ({ex.__class__.__name__}). The events stay in the store.")
            return "RETRY"
        self._wan("ONLINE" if sent else "DEGRADED")
        if not sent:
            self.journal.retry("The cloud did not accept every event in the batch. They stay in the store and are retried.")
        return "SENT" if sent else "RETRY"

    @staticmethod
    def _ms(started: float) -> int:
        return int((time.monotonic() - started) * 1000)

    def _post(self, client: httpx.Client, rows) -> bool:
        started = time.monotonic()
        resp = client.post(f"{self.cfg.cloud_base_url}/ingestion/batch", json=[json.loads(r[2]) for r in rows])
        self._post_ms = self._ms(started)
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
        dead = self.outbox.fail(row[0], error, self.cfg.max_attempts)
        if dead:
            self.counters["dead"] += 1
            log.error("Event %s marked DEAD after %d attempts: %s", row[1], self.cfg.max_attempts, error)
        self.journal.rejected(row[1], str(error)[:200], dead)

    def _done(self, n: int):
        self.counters["forwarded"] += n
        self.last_success_at = time.time()
        self.journal.forwarded(n, self._post_ms)
        log.info("Forwarded %d events to the backend.", n)

    def _wan(self, state: str, why: str = ""):
        if state == self.wan_state:
            return
        self.journal.link_changed(self.wan_state, state, why, at=self._health_at if state == "ONLINE" else None)
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
                self.journal.next_attempt(wait if result == "RETRY" else None)
                self.stop.wait(wait)

    def status(self) -> dict:
        return {
            "wan": self.wan_state, "mqttConnected": self.mqtt_connected, "queue": self.outbox.stats(),
            "lastForwardedAgeSeconds": int(time.time() - self.last_success_at) if self.last_success_at else None,
            "counters": dict(self.counters),
            "local": {
                **self.local.stats(),
                "inputs": [{"device": s.device, "reader": s.reader, "connected": s.connected, "note": s.note} for s in self.local_sources],
                "holdMs": self.cfg.local_hold_ms, "matchSkewMs": self.cfg.local_match_skew_ms,
            },
        }

    # ---- MQTT source --------------------------------------------------------------------------------
    def mqtt_topic_list(self) -> list:
        return [t.strip() for t in self.cfg.mqtt_topics.split(",") if t.strip()]

    def mqtt_label(self, topic: str) -> str:
        """What the log and the console call a topic. The token topic IS the API token, so it is never shown."""
        return TOKEN_NOTICES if topic and topic == self.cfg.token_topic() else topic

    def mqtt_display_topics(self) -> list:
        token = self.cfg.token_topic()
        return ([TOKEN_NOTICES] if token else []) + self.mqtt_topic_list()

    def mqtt_wanted(self) -> list:
        """What to subscribe to, in the order the cloud's own clients use: the token topic, then the topics. One SUBSCRIBE."""
        token = self.cfg.token_topic()
        return [(t, self.cfg.mqtt_qos) for t in ([token] if token else []) + self.mqtt_topic_list()]

    def _mqtt_subscribe(self, subscribe, items: list):
        if not items:
            return
        result = subscribe(list(items))
        if isinstance(result, tuple) and len(result) > 1:
            self._sub_mids[result[1]] = list(items)

    def mqtt_on_connect(self, rc, subscribe):
        """The broker answered the login. `subscribe` is the client's own subscribe."""
        if rc != 0:
            self.mqtt_connected = False
            why, hint = connack_reason(rc)
            log.error("MQTT connect refused (rc=%s): %s %s", rc, why, hint)
            self.journal.mqtt_refused(rc, why, hint)
            return
        wanted = self.mqtt_wanted()
        self.mqtt_connected = True
        self.journal.mqtt_up()
        log.info("MQTT connected to %s; subscribing to %s (QoS %s)", self.cfg.mqtt_url, self.mqtt_display_topics(), self.cfg.mqtt_qos)
        self._mqtt_subscribe(subscribe, wanted)        # on EVERY (re)connect: a clean session forgets them

    def mqtt_on_subscribe(self, mid, granted, subscribe):
        log.info("MQTT subscription granted QoS %s", list(granted))
        items = self._sub_mids.pop(mid, None) or self.mqtt_wanted()
        again = []
        for i, (topic, asked) in enumerate(items):
            answer = granted[i] if i < len(granted) else None
            if answer is None:
                continue
            if answer >= 128 and asked != 0:           # the broker refused this QoS: ask again for that topic at QoS 0
                again.append((topic, 0))
                continue
            self.journal.mqtt_subscribed(self.mqtt_label(topic), answer)   # refused even at QoS 0 is final: do not ask again
            if answer >= 128:
                log.error("The MQTT broker refused the subscription to %s: check the topic and what this account may read.",
                          self.mqtt_label(topic))
        self._mqtt_subscribe(subscribe, again)

    def mqtt_on_disconnect(self, rc):
        was_connected = self.mqtt_connected
        self.mqtt_connected = False
        log.warning("MQTT disconnected (rc=%s); the client reconnects on its own.", rc)
        if was_connected:                              # a refused login is followed by a disconnect: that is not a lost feed
            self.journal.mqtt_down(rc, disconnect_reason(rc))
        try:                                           # no reader copy can arrive now: stop waiting for one
            self.journal.received(self.local.release_all_held())
        except Exception:
            log.exception("Could not release held USB swipes")

    def mqtt_on_connect_fail(self):
        self.mqtt_connected = False
        self.journal.mqtt_attempt_failed()

    def start_mqtt(self):
        if not self.cfg.mqtt_url:
            log.info("MQTT_URL not set: HTTP intake only.")
            return None
        cfg = self.cfg
        client, host, port = build_mqtt_client(cfg)
        topics = self.mqtt_topic_list()
        log.info("MQTT: %s as client '%s', subscribing to %s at QoS %s", clean_broker_url(cfg.mqtt_url), cfg.client_id(),
                 self.mqtt_display_topics() or "nothing", cfg.mqtt_qos)
        if not topics:
            log.warning("MQTT_TOPICS is empty: the gateway will connect to the broker but subscribe to no reader topic.")
        if log.isEnabledFor(logging.DEBUG):             # every MQTT packet, with the token masked
            logging.getLogger("paho.mqtt").addFilter(_Redact(cfg.mqtt_password))
            client.enable_logger(logging.getLogger("paho.mqtt"))

        def on_message(c, userdata, msg):
            try:
                self.handle_mqtt_message(msg.topic, msg.payload)
            except Exception:
                log.exception("Could not queue a message from %s", self.mqtt_label(msg.topic))

        client.on_connect = lambda c, userdata, flags, rc: self.mqtt_on_connect(rc, c.subscribe)
        client.on_subscribe = lambda c, userdata, mid, granted: self.mqtt_on_subscribe(mid, granted, c.subscribe)
        client.on_disconnect = lambda c, userdata, rc: self.mqtt_on_disconnect(rc)
        client.on_connect_fail = lambda c, userdata: self.mqtt_on_connect_fail()
        client.on_message = on_message
        client.reconnect_delay_set(min_delay=1, max_delay=60)
        client.connect_async(host, port, keepalive=30)   # keeps retrying even if the first attempt fails
        client.loop_start()
        return client

    # ---- HTTP intake --------------------------------------------------------------------------------
    def http_server(self) -> ThreadingHTTPServer:
        gw = self

        class Handler(BaseHTTPRequestHandler):
            def _json(self, code: int, body: dict, no_store: bool = False):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                if no_store:
                    self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _console_api(self, method: str):
                answer = gw.console.handle(method, self.path, self.headers)
                if answer is None:
                    return self._json(404, {"error": "not found"})
                self._json(*answer, no_store=True)

            def do_GET(self):
                if self.path.startswith("/status"):
                    self._json(200, gw.status())
                elif self.path.startswith("/health"):
                    self._json(200, {"status": "UP"})
                elif self.path.split("?")[0] in ("/", "/console", "/console/"):
                    body, headers = gw.console.page()          # the page holds no data; the /api calls it makes need the token
                    self.send_response(200)
                    for name, value in headers.items():
                        self.send_header(name, value)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                elif self.path.startswith("/api/"):
                    self._console_api("GET")
                else:
                    self._json(404, {"error": "not found"})

            def do_POST(self):
                if self.path.startswith("/api/"):             # the console has its own token; the ingest token is not needed
                    return self._console_api("POST")
                if gw.cfg.ingest_token and self.headers.get("X-Edge-Token") != gw.cfg.ingest_token:
                    return self._json(401, {"error": "bad token"})
                if self.path.startswith("/local-swipe"):
                    return self._local_swipe()
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

            def _local_swipe(self):
                """{"reader": "DrC0F8", "credential": "38", "device": "optional label"}. The gateway stamps the time."""
                try:
                    body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                    if not isinstance(body, dict) or "reader" not in body or "credential" not in body:
                        raise ValueError('expected {"reader": "...", "credential": "..."}')
                    result = gw.accept_local(str(body["reader"]), str(body.get("device") or "http"), body["credential"])
                except ValueError as ex:
                    return self._json(400, {"error": str(ex)})
                self._json(202, {"state": result.state, "eventKey": result.event_key})

            def log_message(self, fmt, *args):
                return

        return ThreadingHTTPServer((self.cfg.bind_host, self.cfg.bind_port), Handler)


def main():
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    if os.getenv("LOG_LEVEL", "INFO").upper() != "DEBUG":
        logging.getLogger("httpx").setLevel(logging.WARNING)       # one health-check line a second would bury the lines that matter
    gw = Gateway(Config.from_env())
    threading.Thread(target=gw.forward_loop, name="forwarder", daemon=True).start()
    threading.Thread(target=gw.local_loop, name="usb-hold", daemon=True).start()
    gw.start_local_inputs()
    mqtt_client = gw.start_mqtt()
    server = gw.http_server()
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown).start())
    if gw.credential_cards:
        log.info("USB numbers mapped to cards: %s", ", ".join(f"{n}->{c}" for n, c in gw.credential_cards.items()))
    log.info("Barn edge gateway up: HTTP %s:%s, queue %s, backend %s",
             gw.cfg.bind_host, gw.cfg.bind_port, gw.cfg.db_path, gw.cfg.cloud_base_url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        gw.stop.set()
        for src in gw.local_sources:
            src.close()
        if mqtt_client:
            mqtt_client.loop_stop()
            mqtt_client.disconnect()


if __name__ == "__main__":
    main()
