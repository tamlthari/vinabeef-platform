"""
NADCS test server: a stand-in for the national animal-disease reporting API.

It receives the surveillance reports VinaBeef transmits, validates them against spec.py and answers like an agency
would: 202 with a receipt when the report is acceptable, 422 with a list of precise errors when it is not.
It is a TEST DOUBLE and says so in every response ("mock": true). It is not the Department of Animal Health.

Run locally   uvicorn main:app --port 9099         (needs NADCS_MOCK_API_KEYS, e.g. NADCS_MOCK_API_KEYS=test-key)
Render        see README.md / render.yaml          Vercel: deploys as is (FastAPI instance `app` in main.py)

What it keeps (the last 200 submissions) is in memory by default: it is lost on restart, on a Render free-tier spin-down,
and is per-instance on Vercel. Set NADCS_MOCK_DB to a file path (a persistent disk on the host) and the same list is also
written to that SQLite file and read back at start-up, so the history of past transmissions survives restarts.
Each submission is also written to stdout as one JSON line (visible in the host's log).
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
from collections import deque
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

import spec
import ui

try:
    from defusedxml import ElementTree as SafeET
except ImportError:                       # pragma: no cover - defusedxml is in requirements.txt
    SafeET = None

MAX_BODY_BYTES = 30 * 1024 * 1024
KEEP_SUBMISSIONS = 200
PREVIEW_CHARS = 4000
# how much of each received body is kept for the page (bytes). 200 kept submissions x 200 KB is at most ~40 MB of text.
BODY_STORE_BYTES = int(os.environ.get("NADCS_MOCK_BODY_BYTES", "200000"))
BEHAVIORS = ("normal", "reject_all", "server_error", "unavailable", "slow", "rate_limit")
# behaviours that fail on purpose: (HTTP status, status word, text, extra headers)
FAULTS = {
    "server_error": (500, "ERROR", "Simulated internal error (behavior=server_error).", {}),
    "unavailable": (503, "UNAVAILABLE", "Simulated maintenance window (behavior=unavailable).", {"Retry-After": "120"}),
    "rate_limit": (429, "RATE_LIMITED", "Simulated rate limit (behavior=rate_limit).", {"Retry-After": "60"}),
}

app = FastAPI(title="NADCS test server (mock)", version="1.0.0",
              description="A test double of the national animal-disease reporting API. Not the real agency.")

SUBMISSIONS: deque[dict] = deque(maxlen=KEEP_SUBMISSIONS)
SEEN_CONTENT: dict[str, str] = {}            # content hash -> receipt of the first acceptance
STATE = {"behavior": os.environ.get("NADCS_MOCK_BEHAVIOR", "normal"), "slow_seconds": float(os.environ.get("NADCS_MOCK_SLOW_SECONDS", "8"))}
if STATE["behavior"] not in BEHAVIORS:
    STATE["behavior"] = "normal"


def _keys() -> list[str]:
    return [k.strip() for k in os.environ.get("NADCS_MOCK_API_KEYS", "").split(",") if k.strip()]


def _authorize(x_api_key: str | None, authorization: str | None) -> str:
    """Returns a short, non-reversible label of the key used. Raises 401 / 503 as an agency gateway would."""
    keys = _keys()
    if not keys:
        raise HTTPException(503, detail="NADCS_MOCK_API_KEYS is not configured on this server; every request is refused.")
    presented = x_api_key or ""
    if not presented and authorization and authorization.lower().startswith("bearer "):
        presented = authorization[7:].strip()
    for k in keys:
        if presented and hmac.compare_digest(presented.encode(), k.encode()):
            return hashlib.sha256(k.encode()).hexdigest()[:8]
    raise HTTPException(401, detail="Missing or invalid API key (send it in the X-API-Key header).")


def _json(status: int, body: dict, headers: dict | None = None) -> JSONResponse:
    body = {"mock": True, **body}
    return JSONResponse(status_code=status, content=body, headers={"X-Mock-Server": "nadcs-mock", **(headers or {})})


@app.exception_handler(HTTPException)
async def _http_error(_: Request, exc: HTTPException):
    status_word = {401: "UNAUTHORIZED", 404: "NOT_FOUND", 413: "TOO_LARGE", 415: "UNSUPPORTED_MEDIA_TYPE", 503: "NOT_CONFIGURED"}.get(exc.status_code, "ERROR")
    return _json(exc.status_code, {"status": status_word, "summary": str(exc.detail)})


# ---------------------------------------------------------------------------------------------------- parsing
def xml_to_doc(raw: bytes) -> dict[str, Any]:
    """Maps the XML form VinaBeef sends onto the same shape as the JSON form."""
    root = SafeET.fromstring(raw)
    if root.tag != "SurveillanceReport":
        raise ValueError(f"the root element must be SurveillanceReport, not {root.tag[:40]}")
    doc: dict[str, Any] = {"schemaVersion": root.get("schema"), "reportType": root.get("reportType"),
                           "farmScope": root.get("farmScope"), "generatedAt": root.get("generatedAt")}
    count = root.find("RecordCount")
    doc["recordCount"] = count.text.strip() if count is not None and count.text else None
    recs = root.find("Records")
    if recs is not None:
        doc["records"] = [{child.tag: (child.text or "") for child in rec} for rec in recs.findall("Record")]
    return doc


def parse_body(raw: bytes, content_type: str) -> tuple[Any, str]:
    ct = (content_type or "").split(";")[0].strip().lower()
    if ct in ("application/json", "text/json", ""):
        try:
            return json.loads(raw.decode("utf-8")), "JSON"
        except (ValueError, UnicodeDecodeError) as e:
            raise ValueError(f"the body is not valid JSON: {str(e)[:120]}") from e
    if ct in ("application/xml", "text/xml"):
        if SafeET is None:
            raise ValueError("XML support is not installed on this server")
        try:
            return xml_to_doc(raw), "XML"
        except Exception as e:                       # malformed XML, forbidden entities, wrong root
            raise ValueError(f"the body is not a valid SurveillanceReport XML: {str(e)[:120]}") from e
    raise HTTPException(415, detail=f"Content-Type '{ct[:40]}' is not supported; use application/json or application/xml.")


def _content_hash(outcome: spec.Outcome) -> str:
    blob = json.dumps({"t": outcome.report_type, "f": outcome.farm_scope, "r": outcome.records}, sort_keys=True, default=str)
    return hashlib.sha256(blob.encode()).hexdigest()


def _summary(accepted: bool, outcome: spec.Outcome, receipt: str | None) -> str:
    f = outcome.findings
    if accepted:
        w = f" with {f.warning_count} warning{'s' if f.warning_count != 1 else ''}" if f.warning_count else ""
        return f"Accepted: receipt {receipt} ({outcome.record_count} {outcome.report_type} records{w})."
    first = "; ".join(f"{e['path']}: {e['message']}" for e in f.errors[:3])
    more = f" (+{f.error_count - 3} more)" if f.error_count > 3 else ""
    return f"Rejected: {f.error_count} validation error{'s' if f.error_count != 1 else ''}. {first}{more}"


def _log(entry: dict) -> None:
    print(json.dumps({k: entry.get(k) for k in ("submissionId", "receivedAt", "outcome", "reportType", "farmScope", "recordCount",
                                                "errorCount", "warningCount", "receiptId", "httpStatus", "clientRef", "bytes", "sha256")}),
          file=sys.stdout, flush=True)


# ---------------------------------------------------------------------------------------------------- history file
# Optional: NADCS_MOCK_DB=/path/to/file.db keeps the last KEEP_SUBMISSIONS submissions across restarts. The file is a
# convenience, never a dependency: when it cannot be opened or written the server says so once and carries on in memory,
# because a mock that refuses a report over a full disk would be testing the wrong thing.
_DB: dict[str, Any] = {"conn": None, "path": None, "error": None, "write_errors": 0}
_DB_LOCK = threading.Lock()


def _db_path() -> str:
    return os.environ.get("NADCS_MOCK_DB", "").strip()


def _db_fail(what: str, exc: Exception) -> None:
    if not _DB["error"]:
        print(f"[nadcs-mock] history file {_db_path()!r}: {what}: {exc}. Carrying on in memory only.", file=sys.stderr, flush=True)
    _DB["error"] = f"{what}: {exc}"


def _db_open() -> sqlite3.Connection | None:
    """The open history file, or None (persistence off, or the file is unusable). Caller holds _DB_LOCK."""
    path = _db_path()
    if _DB["path"] != path:                               # first use, or the setting changed (tests, a restart in place)
        if _DB["conn"] is not None:
            try:
                _DB["conn"].close()
            except sqlite3.Error:
                pass
        _DB.update(conn=None, path=path, error=None, write_errors=0)
    if not path:
        return None
    if _DB["conn"] is None and not _DB["error"]:
        try:
            folder = os.path.dirname(os.path.abspath(path))
            os.makedirs(folder, exist_ok=True)
            conn = sqlite3.connect(path, check_same_thread=False, timeout=5)
            try:
                conn.execute("PRAGMA journal_mode=WAL")
            except sqlite3.Error:                         # some network file systems cannot do WAL; the default mode still works
                pass
            conn.execute("CREATE TABLE IF NOT EXISTS submissions (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, data TEXT NOT NULL)")
            conn.commit()
            _DB["conn"] = conn
        except (sqlite3.Error, OSError) as e:
            _db_fail("cannot open", e)
    return _DB["conn"]


def history_status() -> dict:
    """What /health and the list report about where the history lives."""
    with _DB_LOCK:
        conn = _db_open()
        return {"persistent": conn is not None, "file": os.path.basename(_db_path()) if conn is not None else None,
                "error": _DB["error"] if _db_path() and conn is None else None, "writeErrors": _DB["write_errors"]}


def _persist(entry: dict) -> None:
    with _DB_LOCK:
        conn = _db_open()
        if conn is None:
            return
        try:
            conn.execute("INSERT INTO submissions (id, data) VALUES (?, ?)", (entry["submissionId"], json.dumps(entry, default=str)))
            conn.execute("DELETE FROM submissions WHERE seq <= (SELECT MAX(seq) FROM submissions) - ?", (KEEP_SUBMISSIONS,))
            conn.commit()
        except sqlite3.Error as e:
            _DB["write_errors"] += 1
            _db_fail("cannot write", e)                    # prints the first failure only


def load_history() -> int:
    """Reads the saved submissions back into memory (oldest first, so the list ends up newest first). Returns how many."""
    SUBMISSIONS.clear()
    SEEN_CONTENT.clear()
    with _DB_LOCK:
        conn = _db_open()
        if conn is None:
            return 0
        try:
            rows = conn.execute("SELECT data FROM (SELECT seq, data FROM submissions ORDER BY seq DESC LIMIT ?) ORDER BY seq ASC", (KEEP_SUBMISSIONS,)).fetchall()
        except sqlite3.Error as e:
            _db_fail("cannot read", e)
            return 0
    for (blob,) in rows:
        try:
            e = json.loads(blob)
        except ValueError:
            continue
        SUBMISSIONS.appendleft(e)
        if e.get("outcome") == "ACCEPTED" and e.get("contentHash") and e.get("receiptId"):
            SEEN_CONTENT.setdefault(e["contentHash"], e["receiptId"])
    return len(SUBMISSIONS)


def _record(entry: dict) -> None:
    now = datetime.now(timezone.utc)
    entry["answeredAt"] = now.isoformat()
    try:
        entry["durationMs"] = max(0, int((now - datetime.fromisoformat(entry["receivedAt"])).total_seconds() * 1000))
    except (KeyError, ValueError):
        entry["durationMs"] = None
    SUBMISSIONS.appendleft(entry)
    _persist(entry)
    _log(entry)


def _client_ref(request: Request) -> str | None:
    """VinaBeef's own transmission id (X-Client-Reference), so a submission can be matched to its row in the sender's log.
    Anything that is not a plain token is dropped rather than stored."""
    v = (request.headers.get("x-client-reference") or "").strip()
    return v if re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", v) else None


def _stored_text(raw: bytes) -> tuple[str, bool]:
    """(text kept for the page, whether it is cut). The cut never leaves half a UTF-8 character at the end."""
    cut = len(raw) > BODY_STORE_BYTES
    chunk = raw[:BODY_STORE_BYTES]
    if cut:
        for end in range(len(chunk), max(len(chunk) - 4, 0), -1):
            try:
                return chunk[:end].decode("utf-8"), True
            except UnicodeDecodeError:
                continue
    return chunk.decode("utf-8", "replace"), cut


def _entry(request: Request, raw: bytes, label: str, received: datetime, sid: str, fmt: str | None = None) -> dict:
    ct = (request.headers.get("content-type") or "")[:80]
    text, cut = _stored_text(raw)
    return {"submissionId": sid, "receivedAt": received.isoformat(), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "format": fmt or ("XML" if "xml" in ct.lower() else "JSON"), "contentType": ct, "clientRef": _client_ref(request),
            "keyLabel": label, "payload": text, "payloadTruncated": cut}


# ---------------------------------------------------------------------------------------------------- endpoints
@app.get("/")
def index(request: Request):
    """A browser (it asks for text/html) lands on the page; every other client gets the JSON description."""
    if "text/html" in request.headers.get("accept", "").lower():
        return RedirectResponse("/ui", status_code=303)
    return _json(200, {"service": "NADCS test server", "note": "A test double of the national animal-disease reporting API. NOT the real agency.",
                       "behavior": STATE["behavior"],
                       "history": history_status(),
                       "endpoints": ["POST /v2/surveillance/herd-health", "POST /v2/surveillance/validate", "GET /v2/surveillance/spec",
                                     "GET /v2/surveillance/submissions", "GET /v2/surveillance/submissions/{id}",
                                     "GET /v2/surveillance/transmissions", "GET|POST /admin/behavior", "GET /health", "GET /ui (browser page)"]})


@app.get("/health")
def health():
    return _json(200, {"status": "ok", "behavior": STATE["behavior"], "keysConfigured": bool(_keys()), "history": history_status()})


@app.get("/v2/surveillance/spec")
def get_spec():
    return _json(200, spec.spec_document())


async def _read(request: Request) -> bytes:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BODY_BYTES:
        raise HTTPException(413, detail=f"The body is larger than {MAX_BODY_BYTES // (1024 * 1024)} MB.")
    raw = await request.body()
    if len(raw) > MAX_BODY_BYTES:
        raise HTTPException(413, detail=f"The body is larger than {MAX_BODY_BYTES // (1024 * 1024)} MB.")
    return raw


def _evaluate(raw: bytes, content_type: str) -> tuple[spec.Outcome | None, str, str | None]:
    """(outcome, format, parse_error)."""
    try:
        doc, fmt = parse_body(raw, content_type)
    except ValueError as e:
        return None, "JSON", str(e)
    return spec.validate(doc, fmt), fmt, None


@app.post("/v2/surveillance/validate")
async def validate_only(request: Request, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    """Dry run: the same validation, nothing is recorded and no receipt is issued."""
    _authorize(x_api_key, authorization)
    outcome, fmt, parse_error = _evaluate(await _read(request), request.headers.get("content-type", ""))
    if parse_error:
        return _json(400, {"status": "REJECTED", "summary": parse_error, "errorCount": 1, "errors": [{"path": "$", "code": "UNREADABLE", "message": parse_error}]})
    f = outcome.findings
    return _json(200 if f.ok else 422, {"status": "VALID" if f.ok else "INVALID", "summary": _summary(f.ok, outcome, None) if not f.ok else
                                        f"Valid ({outcome.record_count} records, {f.warning_count} warnings).",
                                        "format": fmt, "recordCount": outcome.record_count, "errorCount": f.error_count, "errors": f.errors,
                                        "warningCount": f.warning_count, "warnings": f.warnings})


def _early_reject(request: Request, raw: bytes, label: str, received: datetime, sid: str, status: int, code: str, message: str,
                  declared_bytes: int | None = None) -> JSONResponse:
    """A request refused before it could be validated (too large, wrong content type): recorded like any other."""
    entry = _entry(request, raw, label, received, sid)
    if declared_bytes is not None:
        entry.update(bytes=declared_bytes, sha256=None)
    entry.update(outcome="REJECTED", httpStatus=status, reportType=None, farmScope=None, recordCount=None, errorCount=1, warningCount=0,
                 errors=[{"path": "$", "code": code, "message": message}], warnings=[], receiptId=None, summary=message)
    _record(entry)
    word = {413: "TOO_LARGE", 415: "UNSUPPORTED_MEDIA_TYPE"}.get(status, "ERROR")
    return _json(status, {"status": word, "submissionId": sid, "summary": message, "errorCount": 1, "errors": entry["errors"]})


@app.post("/v2/surveillance/herd-health")
async def receive(request: Request, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    label = _authorize(x_api_key, authorization)          # an unauthenticated request is refused and not recorded
    received = datetime.now(timezone.utc)                 # arrival time, even if the behaviour below makes the answer slow
    sid = "SUB-" + received.strftime("%Y%m%d") + "-" + secrets.token_hex(3).upper()
    behavior = STATE["behavior"]

    if behavior in FAULTS:
        status, word, text, extra = FAULTS[behavior]
        try:
            raw = await _read(request)
        except HTTPException:
            raw = b""                                      # a failing server does not care how big it was
        entry = _entry(request, raw, label, received, sid)
        entry.update(outcome="ERROR", httpStatus=status, reportType=None, farmScope=None, recordCount=None, errorCount=1, warningCount=0,
                     errors=[{"path": "$", "code": "INJECTED_FAULT", "message": text}], warnings=[], receiptId=None, summary=text)
        _record(entry)
        return _json(status, {"status": word, "submissionId": sid, "summary": text}, extra)
    if behavior == "slow":
        await asyncio.sleep(STATE["slow_seconds"])

    try:
        raw = await _read(request)
    except HTTPException as e:
        declared = request.headers.get("content-length")
        return _early_reject(request, b"", label, received, sid, e.status_code, "BODY_TOO_LARGE", str(e.detail),
                             int(declared) if declared and declared.isdigit() else 0)
    try:
        outcome, fmt, parse_error = _evaluate(raw, request.headers.get("content-type", ""))
    except HTTPException as e:                              # unsupported content type
        return _early_reject(request, raw, label, received, sid, e.status_code, "UNSUPPORTED_MEDIA_TYPE", str(e.detail))

    entry = _entry(request, raw, label, received, sid, fmt)
    if parse_error:
        entry.update(outcome="REJECTED", httpStatus=400, reportType=None, farmScope=None, recordCount=None, errorCount=1, warningCount=0,
                     errors=[{"path": "$", "code": "UNREADABLE", "message": parse_error}], warnings=[], receiptId=None, summary=parse_error)
        _record(entry)
        return _json(400, {"status": "REJECTED", "submissionId": sid, "summary": parse_error, "errorCount": 1, "errors": entry["errors"]})

    f = outcome.findings
    accepted = f.ok and behavior != "reject_all"
    entry.update(reportType=outcome.report_type, farmScope=outcome.farm_scope, recordCount=outcome.record_count,
                 errorCount=f.error_count if behavior != "reject_all" else max(1, f.error_count),
                 warningCount=f.warning_count, errors=f.errors, warnings=f.warnings)
    if behavior == "reject_all" and f.ok:
        entry["errors"] = [{"path": "$", "code": "POLICY", "message": "Rejected on purpose (behavior=reject_all)."}]
        entry["errorCount"] = 1
    if not accepted:
        summary = _summary(False, outcome, None) if f.error_count else "Rejected: Rejected on purpose (behavior=reject_all)."
        entry.update(outcome="REJECTED", httpStatus=422, receiptId=None, summary=summary)
        _record(entry)
        return _json(422, {"status": "REJECTED", "submissionId": sid, "summary": summary, "errorCount": entry["errorCount"],
                           "errors": entry["errors"], "warningCount": f.warning_count, "warnings": f.warnings})

    receipt = "NADCS-MOCK-" + received.strftime("%Y%m%d") + "-" + secrets.token_hex(3).upper()
    digest = _content_hash(outcome)
    earlier = SEEN_CONTENT.get(digest)
    if earlier:
        f.warn("records", "DUPLICATE_CONTENT", f"identical records for {outcome.report_type}/{outcome.farm_scope} were already accepted (receipt {earlier})")
        entry["warningCount"] = f.warning_count
        entry["warnings"] = f.warnings
    else:
        SEEN_CONTENT[digest] = receipt
        if len(SEEN_CONTENT) > 1000:
            SEEN_CONTENT.pop(next(iter(SEEN_CONTENT)))
    summary = _summary(True, outcome, receipt)
    entry.update(outcome="ACCEPTED", httpStatus=202, receiptId=receipt, summary=summary, contentHash=digest)
    _record(entry)
    return _json(202, {"status": "ACCEPTED", "receiptId": receipt, "submissionId": sid, "receivedAt": entry["receivedAt"],
                       "reportType": outcome.report_type, "farmScope": outcome.farm_scope, "recordCount": outcome.record_count,
                       "summary": summary, "warningCount": f.warning_count, "warnings": f.warnings})


def _public(e: dict, full: bool) -> dict:
    keys = ("submissionId", "receivedAt", "answeredAt", "durationMs", "outcome", "httpStatus", "receiptId", "reportType", "farmScope", "recordCount",
            "errorCount", "warningCount", "format", "bytes", "clientRef", "contentType", "sha256", "summary", "payloadTruncated")
    d = {k: e.get(k) for k in keys}
    if full:
        payload = e.get("payload", "")
        d.update(errors=e.get("errors", []), warnings=e.get("warnings", []), payload=payload,
                 payloadPreview=payload[:PREVIEW_CHARS], payloadStoredBytes=len(payload.encode("utf-8")), keyLabel=e.get("keyLabel"),
                 history=_attempts(e))
    return d


_ATTEMPT_KEYS = ("submissionId", "receivedAt", "outcome", "httpStatus", "receiptId", "summary", "errorCount", "warningCount")


def _attempts(e: dict) -> list[dict]:
    """Every kept submission that carries the same sender reference as e, oldest first: what happened to one VinaBeef
    transmission each time it was tried. A submission without a reference is its own one-entry history."""
    ref = e.get("clientRef")
    same = [x for x in reversed(list(SUBMISSIONS)) if x.get("clientRef") == ref] if ref else []
    return [{k: x.get(k) for k in _ATTEMPT_KEYS} for x in (same or [e])]


def _transmissions() -> list[dict]:
    """One entry per VinaBeef transmission (per sender reference; a submission with none stands alone), newest activity first,
    with the status of every attempt in order. This is the receipt status history."""
    groups: dict[str, list[dict]] = {}
    for e in reversed(list(SUBMISSIONS)):                  # a snapshot, oldest first (a request may append meanwhile)
        groups.setdefault(e.get("clientRef") or e["submissionId"], []).append(e)
    out = []
    for key, es in groups.items():
        last = es[-1]
        receipt = next((x["receiptId"] for x in reversed(es) if x["outcome"] == "ACCEPTED" and x.get("receiptId")), None)
        known = next((x for x in reversed(es) if x.get("reportType")), last)
        out.append({"key": key, "clientRef": es[0].get("clientRef"), "attempts": len(es), "latestSubmissionId": last["submissionId"],
                    "firstReceivedAt": es[0]["receivedAt"], "lastReceivedAt": last["receivedAt"],
                    "latestOutcome": last["outcome"], "latestHttpStatus": last.get("httpStatus"), "receiptId": receipt,
                    "reportType": known.get("reportType"), "farmScope": known.get("farmScope"), "recordCount": known.get("recordCount"),
                    "history": [{"submissionId": x["submissionId"], "receivedAt": x["receivedAt"], "outcome": x["outcome"],
                                 "httpStatus": x.get("httpStatus"), "receiptId": x.get("receiptId")} for x in es]})
    out.sort(key=lambda g: g["lastReceivedAt"], reverse=True)
    return out


@app.get("/v2/surveillance/submissions")
def list_submissions(limit: int = Query(20, ge=1, le=KEEP_SUBMISSIONS), outcome: str | None = Query(None),
                     x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    _authorize(x_api_key, authorization)
    rows = [e for e in SUBMISSIONS if not outcome or e["outcome"] == outcome.upper()]
    counts = {k: sum(1 for e in SUBMISSIONS if e["outcome"] == k) for k in ("ACCEPTED", "REJECTED", "ERROR")}
    hist = history_status()
    return _json(200, {"count": len(rows[:limit]), "kept": len(SUBMISSIONS), "maxKept": KEEP_SUBMISSIONS, "counts": counts,
                       "behavior": STATE["behavior"], "persistent": hist["persistent"],
                       "note": _history_note(hist), "items": [_public(e, False) for e in rows[:limit]]})


def _history_note(hist: dict) -> str:
    if hist["persistent"]:
        return "Saved to the history file: kept across restarts (the last %d)." % KEEP_SUBMISSIONS
    return "In memory only: cleared on restart / spin-down."


@app.get("/v2/surveillance/transmissions")
def list_transmissions(limit: int = Query(50, ge=1, le=KEEP_SUBMISSIONS), outcome: str | None = Query(None),
                       x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    """The receipt status history: one entry per VinaBeef transmission with the outcome of each attempt, oldest to newest.
    outcome filters on the LATEST attempt (a transmission that failed twice and was then accepted counts as ACCEPTED)."""
    _authorize(x_api_key, authorization)
    allx = _transmissions()
    rows = [g for g in allx if not outcome or g["latestOutcome"] == outcome.upper()]
    counts = {k: sum(1 for g in allx if g["latestOutcome"] == k) for k in ("ACCEPTED", "REJECTED", "ERROR")}
    hist = history_status()
    return _json(200, {"count": len(rows[:limit]), "kept": len(allx), "submissionsKept": len(SUBMISSIONS), "maxKept": KEEP_SUBMISSIONS,
                       "counts": counts, "behavior": STATE["behavior"], "persistent": hist["persistent"], "note": _history_note(hist),
                       "items": rows[:limit]})


@app.get("/v2/surveillance/submissions/{submission_id}")
def get_submission(submission_id: str, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    """By submission id, receipt id or the sender's reference (X-Client-Reference); the newest match wins."""
    _authorize(x_api_key, authorization)
    for e in SUBMISSIONS:
        if submission_id in (e["submissionId"], e.get("receiptId"), e.get("clientRef")):
            return _json(200, _public(e, True))
    raise HTTPException(404, detail=f"No submission '{submission_id[:40]}' is kept (the list is in memory and holds the last {KEEP_SUBMISSIONS}).")


@app.get("/ui", response_class=HTMLResponse, include_in_schema=False)
def submissions_page():
    """The page that shows the submissions. It carries no data and no key: it asks for the key and then calls the API above."""
    nonce = secrets.token_urlsafe(16)
    return HTMLResponse(ui.page(nonce), headers={
        "Content-Security-Policy": (f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; "
                                    "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"),
        "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer", "X-Mock-Server": "nadcs-mock"})


@app.get("/admin/behavior")
def get_behavior(x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    _authorize(x_api_key, authorization)
    return _json(200, {"behavior": STATE["behavior"], "available": list(BEHAVIORS), "slowSeconds": STATE["slow_seconds"]})


@app.post("/admin/behavior")
async def set_behavior(request: Request, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    """Switches how the submission endpoint behaves, to test VinaBeef's handling of an agency that rejects or fails."""
    _authorize(x_api_key, authorization)
    try:
        body = await request.json()
    except ValueError:
        body = {}
    mode = str(body.get("mode", "")).strip().lower() if isinstance(body, dict) else ""
    if mode not in BEHAVIORS:
        return _json(400, {"status": "ERROR", "summary": f"mode must be one of {', '.join(BEHAVIORS)}"})
    STATE["behavior"] = mode
    if isinstance(body, dict) and isinstance(body.get("slowSeconds"), (int, float)) and 0 <= body["slowSeconds"] <= 120:
        STATE["slow_seconds"] = float(body["slowSeconds"])
    return _json(200, {"behavior": STATE["behavior"], "slowSeconds": STATE["slow_seconds"]})


try:
    load_history()                      # no-op unless NADCS_MOCK_DB is set
except Exception as _e:                 # pragma: no cover - a damaged history file must never stop the server from starting
    print(f"[nadcs-mock] could not read the history file: {_e}", file=sys.stderr, flush=True)
