"""
Local reads on the barn gateway (USB readers and HTTP sensors): durable keys, the read log, and hold-and-confirm.

A local read is stored at once, in the same SQLite file and the same transaction style as every other event, so a power
cut cannot lose it. What happens next depends on whether the Safetrust reader's own copy of the tap shows up on the MQTT
feed. The MQTT feed is only WATCHED: the cloud backend already receives it directly, so the gateway never queues or
forwards it. It only uses it to decide whether a local read is a copy of something the cloud already has.

  twin already seen   SHADOW  the read is a copy of a reader event the gateway already saw: not forwarded
  MQTT is up          HELD    wait up to hold_ms for the twin; twin arrives -> SHADOW, none arrives -> QUEUED
  MQTT is down        QUEUED  no twin can reach this gateway now: forward at once (the cloud matcher still catches
                              a late copy: it compares the readers' own TIMESTAMP, see ingestion.cross-source.*)

Two kinds of local read:

  USB   a credential number only (no time, no card). The gateway stamps the time and gives it a NEGATIVE EVENT_KEY.
        It is matched to the reader event by reader + credential number.
  HTTP  a whole event from a LAN sensor, with its own EVENT_KEY and TIMESTAMP. It is matched by reader + card (or
        credential number when it has no card). One with no reader, or neither a card nor a credential, cannot be
        matched, so it is queued at once.

The gateway link state is only a shortcut that avoids waiting for nothing. It is NOT proof that no twin exists: the
reader's own uplink can be down while the gateway's is up. That case ends in QUEUED after the hold, and the cloud
matcher is the safety net for everything the edge cannot decide.

EVENT_KEY of a USB swipe is NEGATIVE: -(gateway_no * 10^13 + counter). Safetrust keys are positive, so the two can
never collide, and BIGINT UNIQUE in the cloud stays valid. The counter is persistent and never below the clock in ms,
so even a wiped gateway database cannot hand out a key it used before.
"""
import json
import logging
import statistics
import time
from collections import deque
from dataclasses import dataclass

from usb_input import parse_credential

log = logging.getLogger("edge.local")

SOURCE_LOCAL_USB = "LOCAL_USB"
SOURCE_LOCAL_HTTP = "LOCAL_HTTP"
SOURCE_SAFETRUST = "SAFETRUST"          # a reader event seen on the MQTT feed (watched only, never queued)
LOCAL_ROUTES = (SOURCE_LOCAL_USB, SOURCE_LOCAL_HTTP)
KEY_SPACE = 10 ** 13


@dataclass(frozen=True)
class LocalResult:
    event_key: int
    state: str                 # HELD | QUEUED | SHADOW | DUPLICATE
    twin_key: int = None       # EVENT_KEY of the reader event this read was matched with, if any


@dataclass(frozen=True)
class Observation:
    """What one MQTT reader event did here. It is never stored for sending."""
    duplicate: bool = False    # the same EVENT_KEY was already seen (a broker redelivery)
    usable: bool = False       # it names a reader and a card or credential, so a local read can be matched with it
    released_key: int = None   # EVENT_KEY of the held local read this event confirmed as its copy (now not sent)


def _now_ms() -> int:
    return int(time.time() * 1000)


def _credential_text(value) -> str:
    """Same spelling as a USB swipe: 38, not 0038. Empty when there is none."""
    if value in (None, ""):
        return ""
    return parse_credential(value) or str(value).strip()


class LocalSwipes:
    def __init__(self, outbox, gateway_no: int = 1, hold_ms: int = 6000, skew_ms: int = 10_000,
                 retention_s: int = 86_400, company_key: int = 0, clock=_now_ms, cards: dict = None):
        if not 1 <= gateway_no <= 99:
            raise ValueError("EDGE_GATEWAY_NO must be between 1 and 99")
        self.outbox, self.gateway_no = outbox, gateway_no
        self.hold_ms, self.skew_ms, self.retention_ms = hold_ms, skew_ms, retention_s * 1000
        self.company_key, self.clock = company_key, clock
        self.cards = dict(cards or {})                 # credential number -> card UUID, from LOCAL_CREDENTIAL_MAP (USB only)
        self._offsets = deque(maxlen=50)               # arrival time minus the reader's TIMESTAMP, live MQTT reader events
        with outbox.lock:
            db = outbox.db
            db.execute("""CREATE TABLE IF NOT EXISTS swipe_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                route TEXT NOT NULL,
                reader TEXT NOT NULL,
                credential TEXT NOT NULL,
                arrived_ms INTEGER NOT NULL,
                hw_ms INTEGER NOT NULL,
                event_key INTEGER,
                state TEXT NOT NULL,
                twin_id INTEGER,
                release_at_ms INTEGER)""")
            if "card" not in {r[1] for r in db.execute("PRAGMA table_info(swipe_log)")}:
                db.execute("ALTER TABLE swipe_log ADD COLUMN card TEXT")
            db.execute("CREATE INDEX IF NOT EXISTS idx_swipe_match ON swipe_log(route, state, reader, credential)")
            db.execute("CREATE INDEX IF NOT EXISTS idx_swipe_time ON swipe_log(arrived_ms)")
            db.execute("CREATE INDEX IF NOT EXISTS idx_swipe_key ON swipe_log(route, event_key)")
            db.execute("CREATE TABLE IF NOT EXISTS edge_meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
            db.commit()

    # ---- keys ----------------------------------------------------------------------------------------
    def _next_key(self, db, now_ms: int) -> int:
        """Called inside the caller's transaction: the counter advances atomically with the swipe that uses it."""
        row = db.execute("SELECT v FROM edge_meta WHERE k = 'local_counter'").fetchone()
        counter = max((row[0] + 1) if row else 0, now_ms)
        db.execute("INSERT INTO edge_meta (k, v) VALUES ('local_counter', ?) "
                   "ON CONFLICT(k) DO UPDATE SET v = excluded.v", (counter,))
        return -(self.gateway_no * KEY_SPACE + counter)

    # ---- matching ------------------------------------------------------------------------------------
    @staticmethod
    def _same_card(credential: str, card: str):
        """SQL for 'the same card as this read' and its parameters: the credential number, the card UID, or either."""
        parts, params = [], []
        if credential:
            parts.append("credential = ?")
            params.append(credential)
        if card:
            parts.append("card = ?")
            params.append(card)
        return ("(" + " OR ".join(parts) + ")" if parts else None), params

    def _find_open_twin(self, db, reader, credential, card, arrived_ms, hw_ms):
        """The reader event of the same tap: same reader, same card or credential, close in time on BOTH clocks
        (when it reached the gateway, and the reader's own TIMESTAMP), nearest first, one local read per event."""
        same, params = self._same_card(credential, card)
        if not same or not reader:
            return None
        return db.execute(
            f"SELECT id, event_key FROM swipe_log WHERE route = ? AND state = 'OPEN' AND reader = ? AND {same} "
            "AND ABS(arrived_ms - ?) <= ? AND ABS(hw_ms - ?) <= ? ORDER BY ABS(hw_ms - ?) ASC, id ASC LIMIT 1",
            [SOURCE_SAFETRUST, reader, *params, arrived_ms, self.skew_ms, hw_ms, self.skew_ms, hw_ms]).fetchone()

    def _decide(self, twin, hold: bool, matchable: bool) -> str:
        if twin:
            return "SHADOW"
        return "HELD" if (hold and matchable and self.hold_ms > 0) else "QUEUED"

    def _store(self, db, route, reader, credential, card, arrived_ms, hw_ms, key, event, state, twin, is_card=1):
        """Inside the caller's transaction: the queue row (not for a SHADOW) and the log row, and the claim on the twin."""
        if state != "SHADOW":
            db.execute(
                "INSERT INTO edge_queue (event_key, hardware_ts, payload_json, status, is_card, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (key, hw_ms, json.dumps(event), "HELD" if state == "HELD" else "PENDING", is_card, self.clock()))
        cur = db.execute(
            "INSERT INTO swipe_log (route, reader, credential, card, arrived_ms, hw_ms, event_key, state, twin_id, release_at_ms) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (route, reader, credential, card, arrived_ms, hw_ms, key, state, twin[0] if twin else None,
             arrived_ms + self.hold_ms if state == "HELD" else None))
        if twin:
            db.execute("UPDATE swipe_log SET state = 'CLAIMED', twin_id = ? WHERE id = ?", (cur.lastrowid, twin[0]))

    # ---- a swipe from a USB reader --------------------------------------------------------------------
    def submit(self, reader: str, device: str, credential: str, text: str, t_ms: int, hold: bool) -> LocalResult:
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                twin = self._find_open_twin(db, reader, credential, None, t_ms, t_ms)
                key = self._next_key(db, t_ms)
                state = self._decide(twin, hold, True)
                event = self._event(key, t_ms, reader, device, credential, text)
                self._store(db, SOURCE_LOCAL_USB, reader, credential, None, t_ms, t_ms, key, event, state, twin)
                db.commit()
            except Exception:
                db.rollback()
                raise
        return LocalResult(key, state, twin[1] if twin else None)

    def _event(self, key, t_ms, reader, device, credential, text) -> dict:
        event = {
            "EVENT_KEY": key,
            "TIMESTAMP": t_ms,
            "READER_NAME": reader,
            "CREDENTIAL_IDENTIFIER": credential,
            "EDGE_SOURCE": SOURCE_LOCAL_USB,
            "EDGE_DEVICE": device,
            "EDGE_TIME_SOURCE": "GATEWAY_CLOCK",           # the USB reader sends no time: this is the gateway's
            "rawPayload": json.dumps({"input": text, "device": device, "reader": reader}),
        }
        card = self.cards.get(credential)
        if card:                                       # the cloud then reads it as an ordinary card read, no lookup needed
            event["CARD_UUID"] = card
        if self.company_key:
            event["COMPANY_KEY"] = self.company_key
        return event

    # ---- a read from an HTTP sensor -------------------------------------------------------------------
    def submit_event(self, event: dict, hold: bool, arrived_ms: int = None) -> LocalResult:
        """A whole event from a LAN sensor, already normalised (integer EVENT_KEY and TIMESTAMP). It is sent as it came.
        DUPLICATE means the same EVENT_KEY was taken before (queued, sent or matched): nothing is stored again."""
        key, hw = event["EVENT_KEY"], event["TIMESTAMP"]
        reader = str(event.get("READER_NAME") or "").strip()
        credential = _credential_text(event.get("CREDENTIAL_IDENTIFIER"))
        card = str(event.get("CARD_UUID") or "").strip().upper() or None
        arrived = self.clock() if arrived_ms is None else arrived_ms
        matchable = bool(reader and (credential or card))
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                if (db.execute("SELECT 1 FROM edge_queue WHERE event_key = ?", (key,)).fetchone()
                        or db.execute("SELECT 1 FROM swipe_log WHERE route = ? AND event_key = ? LIMIT 1",
                                      (SOURCE_LOCAL_HTTP, key)).fetchone()):
                    return LocalResult(key, "DUPLICATE")
                twin = self._find_open_twin(db, reader, credential, card, arrived, hw) if matchable else None
                state = self._decide(twin, hold, matchable)
                is_card = 1 if (card or credential) else 0
                if matchable:
                    self._store(db, SOURCE_LOCAL_HTTP, reader, credential, card, arrived, hw, key, event, state, twin, is_card)
                else:                                         # nothing to match on: queue it, no log row
                    db.execute(
                        "INSERT INTO edge_queue (event_key, hardware_ts, payload_json, status, is_card, created_at) "
                        "VALUES (?, ?, ?, 'PENDING', ?, ?)", (key, hw, json.dumps(event), is_card, self.clock()))
                db.commit()
            except Exception:
                db.rollback()
                raise
        return LocalResult(key, state, twin[1] if twin else None)

    # ---- a reader event seen on the MQTT feed -------------------------------------------------------------
    def observe_reader_event(self, event: dict, arrived_ms: int = None) -> Observation:
        """The gateway WATCHES the reader's own feed; it does not send it (the cloud already has it). Remember the event so a
        local read of the same tap can be matched with it, and release a HELD local read that was waiting for exactly this
        event: that read is then a copy and is not sent."""
        reader, hw, key = event.get("READER_NAME"), event.get("TIMESTAMP"), event.get("EVENT_KEY")
        credential = _credential_text(event.get("CREDENTIAL_IDENTIFIER"))
        card = str(event.get("CARD_UUID") or "").strip().upper() or None
        if not reader or not isinstance(hw, int) or not (credential or card):
            return Observation()
        arrived = self.clock() if arrived_ms is None else arrived_ms
        same, params = self._same_card(credential, card)
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                if isinstance(key, int) and db.execute(
                        "SELECT 1 FROM swipe_log WHERE route = ? AND event_key = ? LIMIT 1", (SOURCE_SAFETRUST, key)).fetchone():
                    return Observation(duplicate=True, usable=True)       # a broker redelivery: it must not match a second read
                self._offsets.append(arrived - hw)
                held = db.execute(
                    f"SELECT id, event_key FROM swipe_log WHERE route IN (?, ?) AND state = 'HELD' AND reader = ? AND {same} "
                    "AND ABS(arrived_ms - ?) <= ? AND ABS(hw_ms - ?) <= ? ORDER BY ABS(hw_ms - ?) ASC, id ASC LIMIT 1",
                    [*LOCAL_ROUTES, reader, *params, arrived, self.skew_ms, hw, self.skew_ms, hw]).fetchone()
                cur = db.execute(
                    "INSERT INTO swipe_log (route, reader, credential, card, arrived_ms, hw_ms, event_key, state, twin_id) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (SOURCE_SAFETRUST, reader, credential, card, arrived, hw, key if isinstance(key, int) else None,
                     "CLAIMED" if held else "OPEN", held[0] if held else None))
                if held:
                    db.execute("UPDATE swipe_log SET state = 'SHADOW', twin_id = ?, release_at_ms = NULL WHERE id = ?",
                               (cur.lastrowid, held[0]))
                    db.execute("DELETE FROM edge_queue WHERE event_key = ? AND status = 'HELD'", (held[1],))
                db.commit()
            except Exception:
                db.rollback()
                raise
        return Observation(usable=True, released_key=held[1] if held else None)

    # ---- the end of a hold ----------------------------------------------------------------------------
    def release_due(self, now_ms: int = None) -> int:
        """HELD local reads whose wait is over become QUEUED (forwarded). Safe to call often."""
        return self._release("release_at_ms <= ?", (self.clock() if now_ms is None else now_ms,))

    def release_all_held(self) -> int:
        """Called when MQTT drops: no twin can arrive any more, so waiting longer only delays the read."""
        return self._release("1 = 1", ())

    def _release(self, condition: str, params) -> int:
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                rows = db.execute(
                    f"SELECT id, event_key FROM swipe_log WHERE route IN (?, ?) AND state = 'HELD' AND {condition}",
                    (*LOCAL_ROUTES, *params)).fetchall()
                for log_id, key in rows:
                    db.execute("UPDATE edge_queue SET status = 'PENDING' WHERE event_key = ? AND status = 'HELD'", (key,))
                    db.execute("UPDATE swipe_log SET state = 'QUEUED', release_at_ms = NULL WHERE id = ?", (log_id,))
                db.commit()
            except Exception:
                db.rollback()
                raise
        return len(rows)

    def prune(self, now_ms: int = None) -> int:
        cutoff = (self.clock() if now_ms is None else now_ms) - self.retention_ms
        with self.outbox.lock:
            cur = self.outbox.db.execute("DELETE FROM swipe_log WHERE arrived_ms < ? AND state <> 'HELD'", (cutoff,))
            self.outbox.db.commit()
            return cur.rowcount

    # ---- reporting ------------------------------------------------------------------------------------
    def stats(self) -> dict:
        """What the read log holds for the last retention period (24 h by default), HTTP and USB reads together."""
        with self.outbox.lock:
            rows = dict(self.outbox.db.execute(
                "SELECT state, COUNT(*) FROM swipe_log WHERE route IN (?, ?) GROUP BY state", LOCAL_ROUTES).fetchall())
        return {"held": rows.get("HELD", 0), "queued": rows.get("QUEUED", 0), "shadow": rows.get("SHADOW", 0),
                "clockOffset": self.clock_offset()}

    def clock_offset(self) -> dict:
        """Gateway time at arrival minus the reader's TIMESTAMP, over the last 50 reader events this gateway saw on MQTT.
        On a healthy link that is the reader's clock error plus the network delay: it tells what matching window
        (LOCAL_MATCH_SKEW_MS here, ingestion.cross-source.window-ms in the cloud) is needed. A buffered replay shows
        up as a huge value; the median ignores a few of those."""
        values = list(self._offsets)
        if not values:
            return {"samples": 0, "medianMs": None, "maxAbsMs": None}
        return {"samples": len(values), "medianMs": int(statistics.median(values)), "maxAbsMs": max(abs(v) for v in values)}
