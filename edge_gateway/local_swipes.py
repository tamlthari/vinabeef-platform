"""
Local USB swipes on the barn gateway: durable keys, the swipe log, and hold-and-confirm.

A USB swipe is stored at once, in the same SQLite file and the same transaction style as every other event, so a power
cut cannot lose it. What happens next depends on whether the Safetrust reader's own copy of the tap shows up:

  twin already seen   SHADOW  the swipe is a copy of a read the gateway already holds: not forwarded
  MQTT is up          HELD    wait up to hold_ms for the twin; twin arrives -> SHADOW, none arrives -> QUEUED
  MQTT is down        QUEUED  no twin can reach this gateway now: forward at once (the cloud matcher still catches
                              a late copy: it compares the readers' own TIMESTAMP, see ingestion.cross-source.*)

The gateway link state is only a shortcut that avoids waiting for nothing. It is NOT proof that no twin exists: the
reader's own uplink can be down while the gateway's is up. That case ends in QUEUED after the hold, and the cloud
matcher is the safety net for everything the edge cannot decide.

EVENT_KEY of a local swipe is NEGATIVE: -(gateway_no * 10^13 + counter). Safetrust keys are positive, so the two can
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
SOURCE_SAFETRUST = "SAFETRUST"
KEY_SPACE = 10 ** 13


@dataclass(frozen=True)
class LocalResult:
    event_key: int
    state: str                 # HELD | QUEUED | SHADOW
    twin_key: int = None       # EVENT_KEY of the Safetrust read this swipe was matched with, if any


def _now_ms() -> int:
    return int(time.time() * 1000)


class LocalSwipes:
    def __init__(self, outbox, gateway_no: int = 1, hold_ms: int = 6000, skew_ms: int = 10_000,
                 retention_s: int = 86_400, company_key: int = 0, clock=_now_ms):
        if not 1 <= gateway_no <= 99:
            raise ValueError("EDGE_GATEWAY_NO must be between 1 and 99")
        self.outbox, self.gateway_no = outbox, gateway_no
        self.hold_ms, self.skew_ms, self.retention_ms = hold_ms, skew_ms, retention_s * 1000
        self.company_key, self.clock = company_key, clock
        self._offsets = deque(maxlen=50)               # arrival time minus the reader's TIMESTAMP, live Safetrust reads
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
            db.execute("CREATE INDEX IF NOT EXISTS idx_swipe_match ON swipe_log(route, state, reader, credential)")
            db.execute("CREATE INDEX IF NOT EXISTS idx_swipe_time ON swipe_log(arrived_ms)")
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

    # ---- a swipe from a USB reader --------------------------------------------------------------------
    def submit(self, reader: str, device: str, credential: str, text: str, t_ms: int, hold: bool) -> LocalResult:
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                twin = self._find_open_twin(db, reader, credential, t_ms)
                key = self._next_key(db, t_ms)
                if twin:
                    state = "SHADOW"
                elif hold and self.hold_ms > 0:
                    state = "HELD"
                else:
                    state = "QUEUED"
                if state != "SHADOW":
                    event = self._event(key, t_ms, reader, device, credential, text)
                    db.execute(
                        "INSERT INTO edge_queue (event_key, hardware_ts, payload_json, status, is_card, created_at) "
                        "VALUES (?, ?, ?, ?, 1, ?)",
                        (key, t_ms, json.dumps(event), "HELD" if state == "HELD" else "PENDING", self.clock()))
                cur = db.execute(
                    "INSERT INTO swipe_log (route, reader, credential, arrived_ms, hw_ms, event_key, state, twin_id, release_at_ms) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (SOURCE_LOCAL_USB, reader, credential, t_ms, t_ms, key, state, twin[0] if twin else None,
                     t_ms + self.hold_ms if state == "HELD" else None))
                if twin:
                    db.execute("UPDATE swipe_log SET state = 'CLAIMED', twin_id = ? WHERE id = ?", (cur.lastrowid, twin[0]))
                db.commit()
            except Exception:
                db.rollback()
                raise
        return LocalResult(key, state, twin[1] if twin else None)

    def _find_open_twin(self, db, reader, credential, t_ms):
        """The Safetrust read of the same tap: same reader, same credential, close in time on BOTH clocks
        (when it reached the gateway, and the reader's own TIMESTAMP), nearest first, one swipe per read."""
        return db.execute(
            "SELECT id, event_key FROM swipe_log WHERE route = ? AND state = 'OPEN' AND reader = ? AND credential = ? "
            "AND ABS(arrived_ms - ?) <= ? AND ABS(hw_ms - ?) <= ? ORDER BY ABS(hw_ms - ?) ASC, id ASC LIMIT 1",
            (SOURCE_SAFETRUST, reader, credential, t_ms, self.skew_ms, t_ms, self.skew_ms, t_ms)).fetchone()

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
        if self.company_key:
            event["COMPANY_KEY"] = self.company_key
        return event

    # ---- a Safetrust card read seen by this gateway ------------------------------------------------------
    def record_safetrust(self, event: dict, arrived_ms: int = None):
        """Remember a Safetrust read so a USB swipe of the same tap can be matched with it, and release a HELD swipe
        that was waiting for exactly this read. Returns the EVENT_KEY of the swipe that was released as a copy."""
        credential, reader = event.get("CREDENTIAL_IDENTIFIER"), event.get("READER_NAME")
        hw = event.get("TIMESTAMP")
        if credential in (None, "") or not reader or not isinstance(hw, int):
            return None
        credential = parse_credential(credential) or str(credential).strip()   # same spelling as a USB swipe: 38, not 0038
        arrived = self.clock() if arrived_ms is None else arrived_ms
        self._offsets.append(arrived - hw)
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                held = db.execute(
                    "SELECT id, event_key FROM swipe_log WHERE route = ? AND state = 'HELD' AND reader = ? AND credential = ? "
                    "AND ABS(arrived_ms - ?) <= ? AND ABS(hw_ms - ?) <= ? ORDER BY ABS(hw_ms - ?) ASC, id ASC LIMIT 1",
                    (SOURCE_LOCAL_USB, reader, credential, arrived, self.skew_ms, hw, self.skew_ms, hw)).fetchone()
                cur = db.execute(
                    "INSERT INTO swipe_log (route, reader, credential, arrived_ms, hw_ms, event_key, state, twin_id) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (SOURCE_SAFETRUST, reader, credential, arrived, hw, event.get("EVENT_KEY"),
                     "CLAIMED" if held else "OPEN", held[0] if held else None))
                if held:
                    db.execute("UPDATE swipe_log SET state = 'SHADOW', twin_id = ?, release_at_ms = NULL WHERE id = ?",
                               (cur.lastrowid, held[0]))
                    db.execute("DELETE FROM edge_queue WHERE event_key = ? AND status = 'HELD'", (held[1],))
                db.commit()
            except Exception:
                db.rollback()
                raise
        return held[1] if held else None

    # ---- the end of a hold ----------------------------------------------------------------------------
    def release_due(self, now_ms: int = None) -> int:
        """HELD swipes whose wait is over become QUEUED (forwarded). Safe to call often."""
        return self._release("release_at_ms <= ?", (self.clock() if now_ms is None else now_ms,))

    def release_all_held(self) -> int:
        """Called when MQTT drops: no twin can arrive any more, so waiting longer only delays the swipe."""
        return self._release("1 = 1", ())

    def _release(self, condition: str, params) -> int:
        outbox = self.outbox
        with outbox.lock:
            db = outbox.db
            try:
                rows = db.execute(f"SELECT id, event_key FROM swipe_log WHERE route = ? AND state = 'HELD' AND {condition}",
                                  (SOURCE_LOCAL_USB,) + tuple(params)).fetchall()
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
        with self.outbox.lock:
            rows = dict(self.outbox.db.execute(
                "SELECT state, COUNT(*) FROM swipe_log WHERE route = ? GROUP BY state", (SOURCE_LOCAL_USB,)).fetchall())
        return {"held": rows.get("HELD", 0), "queued": rows.get("QUEUED", 0), "shadow": rows.get("SHADOW", 0),
                "clockOffset": self.clock_offset()}

    def clock_offset(self) -> dict:
        """Gateway time at arrival minus the reader's TIMESTAMP, over the last 50 Safetrust reads this gateway saw.
        On a healthy link that is the reader's clock error plus the network delay: it tells what matching window
        (LOCAL_MATCH_SKEW_MS here, ingestion.cross-source.window-ms in the cloud) is needed. A buffered replay shows
        up as a huge value; the median ignores a few of those."""
        values = list(self._offsets)
        if not values:
            return {"samples": 0, "medianMs": None, "maxAbsMs": None}
        return {"samples": len(values), "medianMs": int(statistics.median(values)), "maxAbsMs": max(abs(v) for v in values)}
