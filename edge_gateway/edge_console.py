"""
Read-only console for the barn edge gateway: what the web page shows, and where each number comes from.

The page is served by the gateway itself (GET /) and reads four endpoints, all on the gateway's own HTTP port:

  GET  /api/overview     link to the cloud, the forward store in numbers, synchronization, USB readers
  GET  /api/queue        browse the forward store: ?status=PENDING|HELD|DEAD  ?q=text  ?order=oldest|newest
                         ?limit=1..200  ?offset=n
  GET  /api/queue/<id>   one event with its full payload
  POST /api/probe        walk the path to the cloud step by step (name lookup, connection, TLS, health check);
                         ?target=mqtt does the same for the MQTT broker (name lookup, connection, TLS, WebSocket upgrade)

Nothing here changes the queue. The store is read through a separate SQLite connection opened with mode=ro, so SQLite itself
refuses any write from this module, and in WAL mode a reader never blocks the intake or the forwarder. The only thing the
console does besides reading is the probe: a GET to the configured cloud health URL, or a connection to the configured MQTT
broker (never an address the caller supplies).

If EDGE_CONSOLE_TOKEN is set, every /api call needs the header X-Edge-Token with that value. The page itself carries no data.

SyncJournal is what the gateway keeps for the page: link changes, what was sent, retried or rejected, local reads taken per minute
(HTTP and USB together), and the state of the MQTT reader feed (connected or not, why, last message, what it is subscribed to).
The MQTT feed is only watched by the gateway, so it is never part of "taken" or "sent".
It lives in memory, so it starts empty when the gateway restarts; the queue itself is durable and is not affected. The same lines
are also written to the gateway's log file (EDGE_LOG_FILE), which does survive a restart.
"""
import base64
import hmac
import json
import logging
import os
import secrets
import shutil
import socket
import sqlite3
import ssl
import threading
import time
from collections import deque
from functools import lru_cache
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import getproxies, proxy_bypass

import httpx

log = logging.getLogger("edge.console")
MAX_PAGE = 200
STATUSES = ("PENDING", "HELD", "DEAD")
NO_ANSWER = "No answer from the broker."
# What the console and the log call the subscription to the password topic. The topic IS the API token: never show it.
TOKEN_NOTICES = "token/… (Safetrust token notices)"

# What the MQTT broker says when it refuses a login (the CONNACK return code), in words a person can act on.
_CONNACK = {
    1: ("The broker does not accept this MQTT version.", ""),
    2: ("The broker refused the client id.",
        "Check MQTT_CLIENT_ID: keep the Web_ prefix and give every program its own id."),
    3: ("The broker is not accepting clients right now.",
        "The gateway keeps trying and connects when the broker is back."),
    4: ("The broker refused the user name or password.",
        "Check MQTT_USERNAME and MQTT_PASSWORD. Safetrust wants token/ followed by the API token, and the token may have expired."),
    5: ("The broker says this user may not connect.",
        "Check MQTT_USERNAME and MQTT_PASSWORD. Safetrust wants token/ followed by the API token, and the token may have expired."),
}


def connack_reason(rc) -> tuple:
    """(why, hint) for a refused MQTT login."""
    return _CONNACK.get(rc, (f"The broker refused the connection (code {rc}).", ""))


def disconnect_reason(rc) -> str:
    if rc == 0:
        return "This gateway closed the connection."
    if rc == 7:
        return "The connection to the broker was lost: the network is down or the broker stopped."
    return f"The connection to the broker ended (code {rc})."


def clean_broker_url(url: str):
    """The broker address without any user name or password in it. None when MQTT is not configured."""
    if not url:
        return None
    u = urlparse(url)
    host = u.hostname or ""
    return f"{u.scheme}://{host}{':' + str(u.port) if u.port else ''}{u.path}"


# --------------------------------------------------------------------------------------------------------
# What the forwarder remembers for the page
# --------------------------------------------------------------------------------------------------------
class SyncJournal:
    """Thread-safe. Written by the forwarder, intake and USB paths; read by the console."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.started = clock()
        self.events = deque(maxlen=200)          # newest last
        self.minutes = {}                        # minute number -> [taken, sent, rejected]; taken = local reads (HTTP and USB)
        self.link = {"state": "UNKNOWN", "since": self.started, "why": ""}
        self.transitions = deque(maxlen=30)
        self.last_probe = None
        self.fail_streak = 0
        self.next_at = None
        # the MQTT reader feed. NOT_CONFIGURED | CONNECTING | CONNECTED | DISCONNECTED | REFUSED
        self.mqtt = {"state": "NOT_CONFIGURED", "since": self.started, "why": "", "hint": "", "code": None, "attempts": 0,
                     "connects": 0, "messages": 0, "events": 0, "last_message": None}
        self.mqtt_subs = {}                      # topic -> {"state": "pending" | "granted" | "refused", "qos": int | None}
        self.mqtt_drops = deque(maxlen=10)       # when the connection was lost, newest last
        self.mqtt_up_at = None                   # when the current/last connection was accepted
        self.mqtt_confirmed = False              # the broker has answered a subscription on this connection
        self.mqtt_early = 0                      # connections in a row that ended before any subscription was confirmed
        self.mqtt_early_ms = None                # how long the last of them lasted

    # ---- writing -------------------------------------------------------------------------------------
    def _bucket(self, now: float) -> list:
        minute = int(now // 60)
        for old in [m for m in self.minutes if m < minute - 61]:
            del self.minutes[old]
        return self.minutes.setdefault(minute, [0, 0, 0])

    def _log(self, now: float, kind: str, n: int, took_ms, text: str, tone=None):
        # a failure that repeats folds into one line. For the reader feed the line to fold into is the last FEED line, so the
        # cloud's own retries in between do not keep it from folding.
        last = next((e for e in reversed(self.events) if e["kind"] == "FEED"), None) if kind == "FEED" else (self.events[-1] if self.events else None)
        if last and last["kind"] == kind and last["text"] == text and kind in ("RETRY", "REJECTED", "FEED"):
            last["repeat"] += 1                   # the same failure again: one line, a count, the latest time
            last["t"], last["_ts"] = int(now * 1000), now
            return
        event = {"t": int(now * 1000), "_ts": now, "kind": kind, "n": n, "ms": took_ms, "text": text, "repeat": 1}
        if tone:
            event["tone"] = tone
        self.events.append(event)

    def taken(self, n: int = 1):
        """A local read (HTTP or USB) arrived. MQTT reader events are not counted here: they are watched, not taken."""
        with self.lock:
            self._bucket(self.clock())[0] += n

    def forwarded(self, n: int, took_ms: int):
        if n <= 0:
            return
        with self.lock:
            now = self.clock()
            self._bucket(now)[1] += n
            self.fail_streak = 0
            self._log(now, "SENT", n, took_ms, f"Sent {n} event{'' if n == 1 else 's'} to the cloud")

    def rejected(self, event_key, error: str, dead: bool):
        with self.lock:
            now = self.clock()
            self._bucket(now)[2] += 1
            self._log(now, "REJECTED", 1, None,
                      f"Event {event_key} refused: {error}" + (" (kept, no more retries)" if dead else ""))

    def retry(self, why: str):
        with self.lock:
            self.fail_streak += 1
            self._log(self.clock(), "RETRY", 0, None, why)

    def probe(self, ok: bool, http, took_ms, error: str = ""):
        with self.lock:
            self.last_probe = {"at": int(self.clock() * 1000), "ok": ok, "http": http, "ms": took_ms, "error": error}

    def healthy(self):
        with self.lock:
            self.fail_streak = 0

    def link_changed(self, old: str, new: str, why: str = "", at=None):
        """`at` lets a recovery be stamped with the moment the cloud answered, so it reads before the events it then sent."""
        with self.lock:
            now = (at - 1e-6) if at else self.clock()      # a hair before anything stamped `at` or later
            self.link = {"state": new, "since": now, "why": why}
            self.transitions.append({"t": int(now * 1000), "from": old, "to": new, "why": why})
            if new == "ONLINE":
                self.fail_streak = 0
            self._log(now, "LINK", 0, None, f"Link {old} to {new}" + (f": {why}" if why else ""))

    def next_attempt(self, wait_s):
        with self.lock:
            self.next_at = self.clock() + wait_s if wait_s else None

    # ---- the MQTT reader feed -------------------------------------------------------------------------
    def mqtt_setup(self, topics):
        """MQTT_URL is set: the gateway is about to connect. Called once, before the client starts."""
        with self.lock:
            self.mqtt.update(state="CONNECTING", since=self.clock(), why="", hint="")
            self.mqtt_subs = {t: {"state": "pending", "qos": None} for t in topics}

    def _mqtt_state(self, state: str, why: str, hint: str, code, tone: str, text: str):
        """Caller holds the lock. The same state for the same reason again keeps its start time and folds in the log."""
        now = self.clock()
        if self.mqtt["state"] != state or self.mqtt["why"] != why:
            self.mqtt.update(state=state, since=now, why=why, hint=hint, code=code)
        self._log(now, "FEED", 0, None, text, tone)

    def mqtt_up(self):
        with self.lock:
            self.mqtt["connects"] += 1
            self.mqtt["attempts"] = 0
            for sub in self.mqtt_subs.values():
                sub.update(state="pending", qos=None)
            self.mqtt_up_at, self.mqtt_confirmed = self.clock(), False
            self._mqtt_state("CONNECTED", "", "", None, "ok", "Reader feed connected")

    def mqtt_subscribed(self, topic: str, granted: int):
        with self.lock:
            refused = granted is None or granted >= 128
            self.mqtt_subs[topic] = {"state": "refused" if refused else "granted", "qos": None if refused else granted}
            self.mqtt_confirmed, self.mqtt_early = True, 0      # the broker answers: it is not hanging up on the subscribe
            if refused:
                self._log(self.clock(), "FEED", 0, None, f"The broker refused the subscription to {topic}", "bad")

    def mqtt_refused(self, code, why: str, hint: str = ""):
        with self.lock:
            self.mqtt["attempts"] += 1
            self._mqtt_state("REFUSED", why, hint, code, "bad", f"Reader feed login refused: {why}")

    def mqtt_down(self, code, why: str):
        with self.lock:
            now = self.clock()
            self.mqtt_drops.append(now)
            if self.mqtt_subs and not self.mqtt_confirmed and self.mqtt_up_at is not None:
                self.mqtt_early += 1                             # accepted, then hung up on before it answered a subscription
                self.mqtt_early_ms = int((now - self.mqtt_up_at) * 1000)
            self._mqtt_state("DISCONNECTED", why, "", code, "warn", f"Reader feed lost: {why}")

    def mqtt_attempt_failed(self):
        """The client could not even open a connection (no network, wrong address, broker stopped)."""
        with self.lock:
            state = self.mqtt["state"]
            if state == "CONNECTED":                       # cannot happen: a live connection is not a failed attempt
                return
            self.mqtt["attempts"] += 1
            if state == "REFUSED":                         # the broker used to answer; now nothing does
                self.mqtt.update(state="DISCONNECTED", since=self.clock(), why=NO_ANSWER, hint="", code=None)
            elif state == "CONNECTING":
                self.mqtt["why"] = NO_ANSWER
            self._log(self.clock(), "FEED", 0, None, "Reader feed: no answer from the broker", "warn")

    def mqtt_message(self, event: bool):
        with self.lock:
            self.mqtt["messages"] += 1
            self.mqtt["events"] += 1 if event else 0
            self.mqtt["last_message"] = self.clock()

    # ---- reading -------------------------------------------------------------------------------------
    def snapshot(self, minutes: int = 30) -> dict:
        with self.lock:
            now = self.clock()
            this = int(now // 60)
            per_minute = []
            for m in range(this - minutes + 1, this + 1):
                r, s, x = self.minutes.get(m, (0, 0, 0))
                per_minute.append({"t": m * 60_000, "taken": r, "sent": s, "rejected": x})
            sent_60s = sum(e["n"] for e in self.events if e["kind"] == "SENT" and e["t"] >= (now - 60) * 1000)
            m = self.mqtt
            drops = sum(1 for t in self.mqtt_drops if t >= now - 300)
            mqtt = {"state": m["state"], "since": int(m["since"] * 1000), "sinceAgeS": int(now - m["since"]), "why": m["why"],
                    "hint": m["hint"], "code": m["code"], "attempts": m["attempts"], "connects": m["connects"],
                    "messages": m["messages"], "events": m["events"],
                    "lastMessageAgeS": int(now - m["last_message"]) if m["last_message"] else None,
                    "topics": [{"topic": t, **v} for t, v in self.mqtt_subs.items()],
                    "drops5min": drops, "flapping": drops >= 3,
                    "earlyDrops": self.mqtt_early, "earlyDropMs": self.mqtt_early_ms}
            return {
                "mqtt": mqtt,
                "startedAt": int(self.started * 1000),
                "link": {**self.link, "since": int(self.link["since"] * 1000),
                         "sinceAgeS": int(now - self.link["since"])},
                "transitions": list(self.transitions)[::-1][:12],
                "lastProbe": dict(self.last_probe, ageS=int(now - self.last_probe["at"] / 1000)) if self.last_probe else None,
                "failStreak": self.fail_streak,
                "nextAttemptInS": max(0, round(self.next_at - now)) if self.next_at else None,
                "perMinute": per_minute,
                "sentLast60s": sent_60s,
                "recent": [{k: v for k, v in e.items() if k != "_ts"}
                           for e in sorted(reversed(self.events), key=lambda e: e["_ts"], reverse=True)[:60]],
            }


# --------------------------------------------------------------------------------------------------------
# The page and its API
# --------------------------------------------------------------------------------------------------------
@lru_cache(maxsize=1)
def _page_template() -> str:
    return Path(__file__).with_name("console.html").read_text(encoding="utf-8")


def _short(value, limit: int = 120):
    if value is None:
        return None
    text = str(value)
    return text if len(text) <= limit else text[:limit] + "…"


def describe(payload: dict, is_card: int) -> dict:
    """What a person wants to see about an event, from its stored payload. Untrusted text is only ever returned as data."""
    notes = payload.get("NOTES") if isinstance(payload.get("NOTES"), dict) else {}
    usb = payload.get("EDGE_SOURCE") == "LOCAL_USB"
    return {
        "kind": "usb" if usb else ("card" if is_card else "reader"),
        "reader": _short(payload.get("READER_NAME")),
        "card": _short(payload.get("CARD_UUID")),
        "credential": _short(payload.get("CREDENTIAL_IDENTIFIER")),
        "device": _short(payload.get("EDGE_DEVICE")),
        "action": _short(notes.get("ACTION") or payload.get("ACTION")),
    }


def _like(text: str) -> str:
    return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


class ConsoleApi:
    def __init__(self, gateway, clock=time.time):
        self.gw = gateway
        self.clock = clock
        self._probe_lock = threading.Lock()
        self._probe_at = 0.0
        self._json_ok = None

    # ---- plumbing ------------------------------------------------------------------------------------
    def _db(self) -> sqlite3.Connection:
        uri = Path(self.gw.cfg.db_path).resolve().as_uri() + "?mode=ro"
        db = sqlite3.connect(uri, uri=True, timeout=2.0)
        db.execute("PRAGMA query_only = ON")
        return db

    def authorised(self, headers) -> bool:
        token = self.gw.cfg.console_token
        return not token or hmac.compare_digest((headers.get("X-Edge-Token", "") or "").encode(), token.encode())

    def page(self):
        """(html bytes, headers). A fresh nonce per response: only this page's own script and style may run."""
        nonce = secrets.token_urlsafe(16)
        html = _page_template().replace("{{NONCE}}", nonce).encode("utf-8")
        return html, {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": (f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; "
                                        "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; "
                                        "frame-ancestors 'none'"),
        }

    def handle(self, method: str, raw_path: str, headers):
        """Routes /api/*. Returns (status, body dict). Anything not an API path returns None."""
        url = urlparse(raw_path)
        path = url.path.rstrip("/")
        if not path.startswith("/api/"):
            return None
        if not self.authorised(headers):
            return 401, {"error": "This gateway console needs its token (header X-Edge-Token)."}
        query = {k: v[-1] for k, v in parse_qs(url.query).items()}
        try:
            if method == "GET" and path == "/api/overview":
                return 200, self.overview()
            if method == "GET" and path == "/api/queue":
                return 200, self.queue(query)
            if method == "GET" and path.startswith("/api/queue/") and path.count("/") == 3:
                event = self.event(path.rsplit("/", 1)[1])
                return (200, event) if event else (404, {"error": "That event is not in the store. It may have been sent already."})
            if method == "POST" and path == "/api/probe":
                return self.probe(query.get("target", "cloud"))
        except ValueError as ex:
            return 400, {"error": str(ex)}
        except sqlite3.Error as ex:
            return 503, {"error": f"The store could not be read: {ex}"}
        except Exception:                      # a surprise in one request must not drop the connection or reach the intake
            log.exception("Console request failed: %s %s", method, path)
            return 500, {"error": "The console could not answer that request. See the gateway log."}
        return 404, {"error": "not found"}

    # ---- overview ------------------------------------------------------------------------------------
    def overview(self) -> dict:
        gw, cfg, now = self.gw, self.gw.cfg, self.clock()
        journal = gw.journal.snapshot()
        with self._db() as db:
            counts = dict(db.execute("SELECT status, COUNT(*) FROM edge_queue GROUP BY status").fetchall())
            oldest = db.execute("SELECT MIN(hardware_ts) FROM edge_queue WHERE status = 'PENDING'").fetchone()[0]
        pending, held, dead = counts.get("PENDING", 0), counts.get("HELD", 0), counts.get("DEAD", 0)
        total = pending + held + dead

        db_path = Path(cfg.db_path).resolve()
        db_bytes = db_path.stat().st_size if db_path.exists() else 0
        wal = Path(str(db_path) + "-wal")
        wal_bytes = wal.stat().st_size if wal.exists() else 0
        disk = shutil.disk_usage(db_path.parent)

        rate = journal["sentLast60s"] / 60.0
        eta = int(pending / rate) if pending and rate > 0 and gw.wan_state == "ONLINE" else None

        cloud = urlparse(cfg.cloud_base_url)
        cloud_url = f"{cloud.scheme}://{cloud.hostname}{':' + str(cloud.port) if cloud.port else ''}{cloud.path}"
        local = dict(gw.status()["local"], mappedCredentials=len(gw.credential_cards))

        return {
            "now": int(now * 1000),
            "gateway": {"name": f"Gateway {cfg.gateway_no}", "host": socket.gethostname(),
                        "uptimeS": int(now - journal["startedAt"] / 1000), "consoleProtected": bool(cfg.console_token)},
            "cloud": {"url": cloud_url, "host": cloud.hostname, "state": gw.wan_state,
                      "sinceMs": journal["link"]["since"], "sinceAgeS": journal["link"]["sinceAgeS"],
                      "why": journal["link"]["why"], "failStreak": journal["failStreak"],
                      "nextAttemptInS": journal["nextAttemptInS"], "lastProbe": journal["lastProbe"],
                      "lastSentAgeS": int(now - gw.last_success_at) if gw.last_success_at else None,
                      "transitions": journal["transitions"]},
            "mqtt": self._mqtt(journal["mqtt"]),
            "store": {"dbPath": str(db_path), "logPath": gw.log_path, "pending": pending, "held": held, "dead": dead, "total": total,
                      "oldestPendingAgeS": int(now - oldest / 1000) if oldest else None,
                      "dbBytes": db_bytes, "walBytes": wal_bytes, "diskFreeBytes": disk.free, "diskTotalBytes": disk.total,
                      "maxRows": cfg.max_queue_rows, "fillPct": round(100.0 * total / cfg.max_queue_rows, 1) if cfg.max_queue_rows else 0,
                      "sendRatePerMin": round(rate * 60, 1), "emptyInS": eta},
            "sync": {"counters": dict(gw.counters), "perMinute": journal["perMinute"], "recent": journal["recent"],
                     "startedAt": journal["startedAt"]},
            "local": local,
        }

    def _mqtt(self, j: dict) -> dict:
        """The reader feed as the page shows it. `connected` is the gateway's own flag (what decides whether a USB swipe
        waits); the rest is what the journal remembers about why and since when."""
        gw, cfg = self.gw, self.gw.cfg
        configured = bool(cfg.mqtt_url)
        u = urlparse(cfg.mqtt_url) if configured else None
        connected = bool(configured and gw.mqtt_connected)
        state = "NOT_CONFIGURED" if not configured else "CONNECTED" if connected else (
            j["state"] if j["state"] not in ("CONNECTED", "NOT_CONFIGURED") else "DISCONNECTED")
        topics = j["topics"] if j["topics"] else [{"topic": t, "state": "pending", "qos": None} for t in gw.mqtt_display_topics()]
        warning = None
        if configured and not gw.mqtt_topic_list():
            warning = "MQTT_TOPICS is empty. This gateway can connect to the broker but is subscribed to nothing."
        elif configured and j["earlyDrops"] >= 2:
            warning = (f"The broker hangs up about {j['earlyDropMs']} ms after the login, before it confirms the subscription "
                       f"({j['earlyDrops']} times in a row). The login itself works, so the address, user name and token are right. "
                       "Safetrust's own subscribers use a client id that ends with the device key, subscribe to token/<API token> "
                       "as well as the topic, and ask for QoS 2. Check MQTT_CLIENT_ID, MQTT_QOS and MQTT_TOKEN_TOPIC.")
        elif connected and any(t["state"] == "refused" for t in topics):
            bad = next(t["topic"] for t in topics if t["state"] == "refused")
            warning = (f"The broker refused the subscription to {bad}. Check the topic and what this account may read."
                       if bad != TOKEN_NOTICES else
                       "The broker refused the token subscription. The token may have expired; MQTT_TOKEN_TOPIC=false stops asking for it.")
        elif j["flapping"]:
            warning = (f"The connection dropped {j['drops5min']} times in the last 5 minutes. A broker disconnects a client "
                       "when another program connects with the same MQTT_CLIENT_ID: give each its own.")
        return {"configured": configured, "connected": connected, "broker": u.hostname if u else None,
                "url": clean_broker_url(cfg.mqtt_url), "clientId": cfg.client_id() if configured else None,
                "state": state, "sinceMs": j["since"], "sinceAgeS": j["sinceAgeS"], "why": j["why"], "hint": j["hint"],
                "code": j["code"], "attempts": j["attempts"], "connects": j["connects"], "messages": j["messages"],
                "events": j["events"], "lastMessageAgeS": j["lastMessageAgeS"], "topics": topics, "warning": warning,
                "watchedOnly": True,
                "drops5min": j["drops5min"], "holdMs": cfg.local_hold_ms,
                "holding": bool(connected and cfg.local_hold_ms > 0)}

    # ---- browsing the store --------------------------------------------------------------------------
    def queue(self, query: dict) -> dict:
        status = (query.get("status") or "").upper()
        if status and status != "ALL" and status not in STATUSES:
            raise ValueError("status must be PENDING, HELD, DEAD or ALL")
        order = query.get("order", "oldest")
        if order not in ("oldest", "newest"):
            raise ValueError("order must be oldest or newest")
        try:
            limit = min(MAX_PAGE, max(1, int(query.get("limit", 50))))
            offset = max(0, int(query.get("offset", 0)))
        except ValueError:
            raise ValueError("limit and offset must be whole numbers")

        where, args = [], []
        if status in STATUSES:
            where.append("status = ?")
            args.append(status)
        text = (query.get("q") or "").strip()[:64]
        if text:
            clause, args2 = self._search_clause(text)
            where.append(clause)
            args += args2
        cond = ("WHERE " + " AND ".join(where)) if where else ""
        direction = "ASC" if order == "oldest" else "DESC"

        with self._db() as db:
            total = db.execute(f"SELECT COUNT(*) FROM edge_queue {cond}", args).fetchone()[0]
            rows = db.execute(
                f"SELECT seq_id, event_key, hardware_ts, created_at, status, attempts, last_error, is_card, payload_json "
                f"FROM edge_queue {cond} ORDER BY hardware_ts {direction}, seq_id {direction} LIMIT ? OFFSET ?",
                args + [limit, offset]).fetchall()
        now = self.clock()
        out = []
        for seq, key, hw, created, st, attempts, err, is_card, payload_json in rows:
            try:
                payload = json.loads(payload_json)
                payload = payload if isinstance(payload, dict) else {}
            except ValueError:
                payload = {}
            out.append({"id": seq, "eventKey": key, "eventAt": hw, "storedAt": created or None, "status": st,
                        "attempts": attempts, "lastError": _short(err, 200), "waitingS": max(0, int(now - hw / 1000)),
                        **describe(payload, is_card)})
        return {"total": total, "limit": limit, "offset": offset, "rows": out}

    def _search_clause(self, text: str):
        """Text matches the card, credential, reader or device (never the timestamps inside the payload); digits also
        match the event number exactly. SQLite's JSON functions pick the fields; without them the whole payload is searched."""
        pattern = _like(text)
        if self._json_ok is None:
            try:
                sqlite3.connect(":memory:").execute("SELECT json_extract('{}', '$')")
                self._json_ok = True
            except sqlite3.Error:
                self._json_ok = False
        if self._json_ok:
            fields = ("$.CARD_UUID", "$.CREDENTIAL_IDENTIFIER", "$.READER_NAME", "$.EDGE_DEVICE")
            clause = "(" + " OR ".join(f"json_extract(payload_json, '{f}') LIKE ? ESCAPE '\\'" for f in fields) + ")"
            args = [pattern] * len(fields)
        else:
            clause, args = "payload_json LIKE ? ESCAPE '\\'", [pattern]
        if text.lstrip("-").isdigit():
            clause, args = f"(event_key = ? OR {clause})", [int(text)] + args
        return clause, args

    def event(self, raw_id: str):
        try:
            seq = int(raw_id)
        except ValueError:
            raise ValueError("the event id must be a whole number")
        with self._db() as db:
            row = db.execute("SELECT seq_id, event_key, hardware_ts, created_at, status, attempts, last_error, is_card, payload_json "
                             "FROM edge_queue WHERE seq_id = ?", (seq,)).fetchone()
        if not row:
            return None
        seq, key, hw, created, st, attempts, err, is_card, payload_json = row
        try:
            payload = json.loads(payload_json)
            pretty = json.dumps(payload, indent=2, ensure_ascii=False)
        except ValueError:
            payload, pretty = {}, payload_json
        cut = len(pretty) > 20_000
        return {"id": seq, "eventKey": key, "eventAt": hw, "storedAt": created or None, "status": st, "attempts": attempts,
                "lastError": err, "maxAttempts": self.gw.cfg.max_attempts,
                **describe(payload if isinstance(payload, dict) else {}, is_card),
                "payload": pretty[:20_000], "payloadCut": cut}

    # ---- the path to the cloud -----------------------------------------------------------------------
    def probe(self, target: str = "cloud"):
        """Name lookup, TCP connection, TLS handshake, then the same health check the forwarder makes (target=cloud),
        or the first steps of an MQTT connection to the configured broker (target=mqtt)."""
        cfg = self.gw.cfg
        if target not in ("cloud", "mqtt"):
            raise ValueError("target must be cloud or mqtt")
        if target == "mqtt" and not cfg.mqtt_url:
            raise ValueError("MQTT_URL is not set on this gateway, so there is no broker to check.")
        with self._probe_lock:
            wait = 2.0 - (self.clock() - self._probe_at)
            if wait > 0:
                return 429, {"error": "A check was just made. Try again in a moment.", "retryInS": round(wait + 0.5)}
            self._probe_at = self.clock()
            if target == "mqtt":
                result = run_broker_probe(cfg.mqtt_url, cfg.mqtt_tls_insecure, cfg.mqtt_ca_certs)
            else:
                result = run_probe(cfg.cloud_base_url + "/ingestion/health")
            return 200, dict(result, target=target)


def _step_runner(steps: list):
    """run(name, label, fn): call fn, record how it went. A failure is a result, never an exception."""
    def run(name, label, fn):
        t0 = time.monotonic()
        try:
            detail = fn()
            steps.append({"name": name, "label": label, "ok": True, "ms": int((time.monotonic() - t0) * 1000), "detail": detail})
            return True
        except Exception as ex:  # noqa: BLE001 - every failure is a result here, not an error
            text = str(ex) or ex.__class__.__name__
            steps.append({"name": name, "label": label, "ok": False, "ms": int((time.monotonic() - t0) * 1000),
                          "error": f"{ex.__class__.__name__}: {text}"[:240]})
            return False
    return run


def _open_path(run, steps: list, host: str, port: int, tls: bool, timeout: float, make_ctx, plain_note: str):
    """Name lookup, TCP connection, then TLS when the address is encrypted. Returns (ok, socket-or-None); the caller closes it.
    make_ctx() returns (ssl context, what to say about the certificate)."""
    state = {}

    def dns():
        info = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        state["addr"] = info[0][4]
        state["family"] = info[0][0]
        return state["addr"][0]

    def tcp():
        sock = socket.socket(state["family"], socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            sock.connect(state["addr"])
        except Exception:
            sock.close()
            raise
        state["sock"] = sock
        return f"port {port} open"

    def handshake():
        ctx, note = make_ctx()
        wrapped = ctx.wrap_socket(state["sock"], server_hostname=host)
        state["sock"] = wrapped
        return f"{wrapped.version()}, {note}"

    ok = run("dns", "Find the server", dns) and run("tcp", "Open a connection", tcp)
    if ok and tls:
        ok = run("tls", "Secure the connection", handshake)
    elif ok:
        steps.append({"name": "tls", "label": "Secure the connection", "ok": True, "ms": 0, "skipped": True, "detail": plain_note})
    return ok, state.get("sock")


def _close(sock):
    if sock:
        try:
            sock.close()
        except OSError:
            pass


def run_probe(url: str, timeout: float = 5.0) -> dict:
    u = urlparse(url)
    tls = u.scheme == "https"
    port = u.port or (443 if tls else 80)
    proxies = getproxies()                       # what httpx (and so the forwarder) will use: the environment, minus NO_PROXY
    via_proxy = bool((proxies.get(u.scheme) or proxies.get("all")) and not proxy_bypass(u.hostname or ""))
    steps, started = [], time.monotonic()
    run = _step_runner(steps)

    ok = True
    if via_proxy:
        steps.append({"name": "proxy", "label": "Network path", "ok": True, "ms": 0, "skipped": True,
                      "detail": "This gateway reaches the cloud through a proxy, so only the health check is made."})
    else:
        ok, sock = _open_path(run, steps, u.hostname, port, tls, timeout,
                              lambda: (httpx.create_ssl_context(), "certificate accepted"), "Not used: the cloud address is plain http.")
        _close(sock)

    health_http = {}

    def health():
        try:
            r = httpx.get(url, timeout=timeout)
        except httpx.HTTPError as ex:
            health_http["kind"] = ("connect" if isinstance(ex, (httpx.ConnectError, httpx.ConnectTimeout, httpx.ProxyError))
                                   else "slow" if isinstance(ex, httpx.TimeoutException) else "other")
            raise
        health_http["status"] = r.status_code
        if r.status_code != 200:
            raise RuntimeError(f"the cloud answered HTTP {r.status_code}")
        return "HTTP 200"

    if ok:
        ok = run("health", "Ask the cloud if it can take events", health)
    else:
        steps.append({"name": "health", "label": "Ask the cloud if it can take events", "ok": None, "ms": 0,
                      "detail": "Not tried: an earlier step failed."})

    failed = next((s for s in steps if s["ok"] is False), None)
    return {"ok": bool(ok), "steps": steps, "totalMs": int((time.monotonic() - started) * 1000), "checkedAt": int(time.time() * 1000),
            "viaProxy": via_proxy, "url": url, "healthHttp": health_http.get("status"),
            "hint": hint_for(failed, port, health_http.get("status"), steps, health_http.get("kind"), via_proxy)}


def hint_for(failed, port: int, http_status, steps, kind=None, via_proxy=False) -> str:
    if failed is None:
        health = next((s for s in steps if s["name"] == "health"), None)
        return f"The cloud is reachable. The health check answered in {health['ms']} ms." if health else "The cloud is reachable."
    name = failed["name"]
    if name == "dns":
        return "This gateway cannot find the cloud server's address. Check the barn's Internet connection and the router's DNS setting."
    if name == "tcp":
        return (f"The address is found, but the connection is refused or times out. The Internet may be down, "
                f"or a firewall is blocking port {port}.")
    if name == "tls":
        return ("The connection opens but the secure handshake fails. Check the gateway's date and time first, "
                "then the server's certificate.")
    if http_status:
        return (f"The server is reachable but unhealthy (HTTP {http_status}). Events stay in the store and are retried; "
                "nothing is lost.")
    if kind == "connect":
        return ("This gateway could not connect to the cloud" + (" through its proxy" if via_proxy else "") +
                ". The Internet or the " + ("proxy" if via_proxy else "route out of the barn") +
                " may be down. Events stay in the store and are retried.")
    if kind == "slow":
        return "The connection opened but the cloud did not answer in time. Events stay in the store and are retried."
    return "The health check failed. Events stay in the store and are retried; nothing is lost."


def run_broker_probe(url: str, tls_insecure: bool = True, ca_certs: str = "", timeout: float = 5.0) -> dict:
    """The first steps of what the MQTT client does: find the broker, open a connection, secure it, and for a WebSocket address
    ask the server to upgrade. It cannot log in (that needs the password), so it says so: the reader feed status shows that."""
    u = urlparse(url)
    scheme = u.scheme
    websockets, tls = scheme in ("ws", "wss"), scheme in ("wss", "mqtts", "ssl")
    port = u.port or (443 if scheme == "wss" else 80 if scheme == "ws" else 8883 if tls else 1883)
    steps, started = [], time.monotonic()
    run = _step_runner(steps)

    def make_ctx():
        if tls_insecure:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            return ctx, "certificate not checked (MQTT_TLS_INSECURE is on)"
        return ssl.create_default_context(cafile=ca_certs or None), "certificate accepted"

    ok, sock = _open_path(run, steps, u.hostname or "", port, tls, timeout, make_ctx, "Not used: the broker address is not encrypted.")
    if websockets:
        def upgrade():
            path = u.path or "/mqtt"
            request = (f"GET {path} HTTP/1.1\r\nHost: {u.hostname}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {base64.b64encode(os.urandom(16)).decode()}\r\nSec-WebSocket-Version: 13\r\n"
                       "Sec-WebSocket-Protocol: mqtt\r\n\r\n")
            sock.settimeout(timeout)
            sock.sendall(request.encode())
            data = b""
            while b"\r\n\r\n" not in data and len(data) < 4096:
                chunk = sock.recv(1024)
                if not chunk:
                    break
                data += chunk
            line = data.split(b"\r\n", 1)[0].decode("latin-1")
            parts = line.split()
            if len(parts) < 2 or parts[1] != "101":
                raise RuntimeError(f"the server answered '{line[:60] or 'nothing'}' instead of switching to a WebSocket")
            return f"HTTP 101 on {path}"

        label = "Open the MQTT endpoint"
        if ok:
            ok = run("ws", label, upgrade)
        else:
            steps.append({"name": "ws", "label": label, "ok": None, "ms": 0, "detail": "Not tried: an earlier step failed."})
    _close(sock)
    steps.append({"name": "login", "label": "Log in with the user name and password", "ok": None, "ms": 0,
                  "detail": "Not tried here. The reader feed status above shows whether the broker accepts the login."})
    failed = next((s for s in steps if s["ok"] is False), None)
    return {"ok": bool(ok), "steps": steps, "totalMs": int((time.monotonic() - started) * 1000), "checkedAt": int(time.time() * 1000),
            "viaProxy": False, "url": clean_broker_url(url), "hint": broker_hint(failed, port)}


def broker_hint(failed, port: int) -> str:
    if failed is None:
        return "The broker is reachable. Whether it accepts the login is shown in the reader feed status."
    name = failed["name"]
    if name == "dns":
        return "This gateway cannot find the broker's address. Check the Internet connection, the router's DNS setting and MQTT_URL."
    if name == "tcp":
        return (f"The address is found, but the connection is refused or times out. The Internet may be down, "
                f"a firewall may block port {port}, or the broker is not running.")
    if name == "tls":
        return ("The connection opens but the secure handshake fails. Check the gateway's date and time, then MQTT_URL "
                "(use ws:// or mqtt:// for a broker without TLS) and MQTT_CA_CERTS.")
    return "The server answered, but not as an MQTT WebSocket endpoint. Check the path at the end of MQTT_URL (Safetrust uses /mqtt)."
