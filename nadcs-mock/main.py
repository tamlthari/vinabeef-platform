"""
NADCS test server: a stand-in for the national animal-disease reporting API.

It receives the surveillance reports VinaBeef transmits, validates them against spec.py and answers like an agency
would: 202 with a receipt when the report is acceptable, 422 with a list of precise errors when it is not.
It is a TEST DOUBLE and says so in every response ("mock": true). It is not the Department of Animal Health.

Run locally   uvicorn main:app --port 9099         (needs NADCS_MOCK_API_KEYS, e.g. NADCS_MOCK_API_KEYS=test-key)
Render        see README.md / render.yaml          Vercel: deploys as is (FastAPI instance `app` in main.py)

Everything it keeps (the list of submissions) is in memory: it is lost on restart, on a Render free-tier spin-down,
and is per-instance on Vercel. Each submission is also written to stdout as one JSON line (visible in the host's log).
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import secrets
import sys
from collections import deque
from datetime import datetime, timezone
from typing import Any

import spec
from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse

try:
    from defusedxml import ElementTree as SafeET
except ImportError:                       # pragma: no cover - defusedxml is in requirements.txt
    SafeET = None

MAX_BODY_BYTES = 30 * 1024 * 1024
KEEP_SUBMISSIONS = 200
PREVIEW_CHARS = 4000
BEHAVIORS = ("normal", "reject_all", "server_error", "unavailable", "slow", "rate_limit")

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
    print(json.dumps({k: entry[k] for k in ("submissionId", "receivedAt", "outcome", "reportType", "farmScope", "recordCount",
                                            "errorCount", "warningCount", "receiptId", "httpStatus")}), file=sys.stdout, flush=True)


# ---------------------------------------------------------------------------------------------------- endpoints
@app.get("/")
def index():
    return _json(200, {"service": "NADCS test server", "note": "A test double of the national animal-disease reporting API. NOT the real agency.",
                       "behavior": STATE["behavior"],
                       "endpoints": ["POST /v2/surveillance/herd-health", "POST /v2/surveillance/validate", "GET /v2/surveillance/spec",
                                     "GET /v2/surveillance/submissions", "GET /v2/surveillance/submissions/{id}", "GET|POST /admin/behavior", "GET /health"]})


@app.get("/health")
def health():
    return _json(200, {"status": "ok", "behavior": STATE["behavior"], "keysConfigured": bool(_keys())})


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


@app.post("/v2/surveillance/herd-health")
async def receive(request: Request, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    label = _authorize(x_api_key, authorization)
    behavior = STATE["behavior"]
    if behavior == "server_error":
        return _json(500, {"status": "ERROR", "summary": "Simulated internal error (behavior=server_error)."})
    if behavior == "unavailable":
        return _json(503, {"status": "UNAVAILABLE", "summary": "Simulated maintenance window (behavior=unavailable)."}, {"Retry-After": "120"})
    if behavior == "rate_limit":
        return _json(429, {"status": "RATE_LIMITED", "summary": "Simulated rate limit (behavior=rate_limit)."}, {"Retry-After": "60"})
    if behavior == "slow":
        await asyncio.sleep(STATE["slow_seconds"])

    raw = await _read(request)
    received = datetime.now(timezone.utc)
    sid = "SUB-" + received.strftime("%Y%m%d") + "-" + secrets.token_hex(3).upper()
    outcome, fmt, parse_error = _evaluate(raw, request.headers.get("content-type", ""))

    entry: dict[str, Any] = {"submissionId": sid, "receivedAt": received.isoformat(), "format": fmt, "bytes": len(raw), "keyLabel": label,
                             "payloadPreview": raw[:PREVIEW_CHARS].decode("utf-8", "replace")}
    if parse_error:
        entry.update(outcome="REJECTED", httpStatus=400, reportType=None, farmScope=None, recordCount=0, errorCount=1, warningCount=0,
                     errors=[{"path": "$", "code": "UNREADABLE", "message": parse_error}], warnings=[], receiptId=None)
        SUBMISSIONS.appendleft(entry); _log(entry)
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
        entry.update(outcome="REJECTED", httpStatus=422, receiptId=None)
        SUBMISSIONS.appendleft(entry); _log(entry)
        summary = _summary(False, outcome, None) if f.error_count else "Rejected: Rejected on purpose (behavior=reject_all)."
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
    entry.update(outcome="ACCEPTED", httpStatus=202, receiptId=receipt)
    SUBMISSIONS.appendleft(entry); _log(entry)
    return _json(202, {"status": "ACCEPTED", "receiptId": receipt, "submissionId": sid, "receivedAt": entry["receivedAt"],
                       "reportType": outcome.report_type, "farmScope": outcome.farm_scope, "recordCount": outcome.record_count,
                       "summary": _summary(True, outcome, receipt), "warningCount": f.warning_count, "warnings": f.warnings})


def _public(e: dict, full: bool) -> dict:
    keys = ("submissionId", "receivedAt", "outcome", "httpStatus", "receiptId", "reportType", "farmScope", "recordCount",
            "errorCount", "warningCount", "format", "bytes")
    d = {k: e.get(k) for k in keys}
    if full:
        d.update(errors=e.get("errors", []), warnings=e.get("warnings", []), payloadPreview=e.get("payloadPreview", ""), keyLabel=e.get("keyLabel"))
    return d


@app.get("/v2/surveillance/submissions")
def list_submissions(limit: int = Query(20, ge=1, le=KEEP_SUBMISSIONS), outcome: str | None = Query(None),
                     x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    _authorize(x_api_key, authorization)
    rows = [e for e in SUBMISSIONS if not outcome or e["outcome"] == outcome.upper()]
    return _json(200, {"count": len(rows[:limit]), "kept": len(SUBMISSIONS),
                       "note": "In memory only: cleared on restart / spin-down.", "items": [_public(e, False) for e in rows[:limit]]})


@app.get("/v2/surveillance/submissions/{submission_id}")
def get_submission(submission_id: str, x_api_key: str | None = Header(None), authorization: str | None = Header(None)):
    _authorize(x_api_key, authorization)
    for e in SUBMISSIONS:
        if e["submissionId"] == submission_id or e.get("receiptId") == submission_id:
            return _json(200, _public(e, True))
    raise HTTPException(404, detail=f"No submission '{submission_id[:40]}' is kept (the list is in memory and holds the last {KEEP_SUBMISSIONS}).")


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
