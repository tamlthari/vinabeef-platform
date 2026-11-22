"""
Validation rules of the NADCS test server.

IMPORTANT: these are VinaBeef's own requirements for what a surveillance report must contain, derived from the
reports the VinaBeef backend produces (SurveillanceQueries / SurveillanceReportController) and from basic data
integrity. They are NOT the official rules of the Department of Animal Health: no public specification of
api.nadcs.dah.gov.vn was found. When the real specification arrives, change this file (and only this file).

Rule levels
  error   -> the whole submission is rejected (HTTP 422); nothing is stored as accepted.
  warning -> the submission is accepted (HTTP 202) and the warning is returned in the receipt.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

MAX_RECORDS = 50_000                      # same cap as the VinaBeef backend
MAX_ERRORS_KEPT = 200                     # errors listed in a response; the total is always reported
FUTURE_TOLERANCE = timedelta(minutes=5)   # clock skew allowed on "now"
STALE_AFTER = timedelta(hours=24)         # generatedAt older than this -> warning
EARLIEST = datetime(2000, 1, 1, tzinfo=timezone.utc)

AGENCY = "National Animal Disease Control System (NADCS)"
SCHEMA_JSON = "NADCS-2.1"
SCHEMA_XML = "NADCS-WOAH-2.1"
REPORT_TYPES = ("OUTBREAK_SURVEILLANCE", "MOVEMENT_TRANSFERS", "HEALTH_TREATMENTS", "VACCINATION_COVERAGE")

HEALTH_STATUSES = ("HEALTHY", "UNDER_TREATMENT", "QUARANTINE")
RECORD_TYPES = ("VACCINATION", "TREATMENT", "HEALTH_CHECK", "QUARANTINE")
FARM_SCOPE_RE = re.compile(r"^(ALL|[A-Za-z0-9][A-Za-z0-9_.-]{0,63})$")
TS_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$")
NUM_RE = re.compile(r"^-?\d+(\.\d+)?$")
INT_RE = re.compile(r"^-?\d+$")


@dataclass(frozen=True)
class Field:
    name: str
    kind: str                     # str | int | num | ts | json_object
    nullable: bool = False
    enum: tuple | None = None
    minv: float | None = None
    maxv: float | None = None
    maxlen: int | None = None


def S(name, nullable=False, enum=None, maxlen=200): return Field(name, "str", nullable, enum, maxlen=maxlen)
def I(name, minv=0, maxv=10_000_000): return Field(name, "int", False, None, minv, maxv)
def N(name, minv=0.0, maxv=100.0): return Field(name, "num", False, None, minv, maxv)
def T(name, nullable=False): return Field(name, "ts", nullable)


# every field below must be present in every record; "nullable" fields may be null / empty
RECORD_FIELDS: dict[str, list[Field]] = {
    "OUTBREAK_SURVEILLANCE": [
        S("farm_id", maxlen=64), S("farm_name"), S("location", nullable=True, maxlen=300),
        I("barn_count"), I("active_herd_count"), I("healthy_count"), I("under_treatment_count"),
        I("quarantined_count"), I("vaccinated_any_count"), I("fmd_vaccinated_count"), I("lsd_vaccinated_count"),
    ],
    "MOVEMENT_TRANSFERS": [
        S("transfer_id", maxlen=64), S("animal_id", maxlen=64), S("transfer_type", maxlen=40),
        S("origin_farm", nullable=True), S("origin_barn", nullable=True),
        S("destination_farm", nullable=True), S("destination_barn", nullable=True),
        S("reason", nullable=True, maxlen=500), S("approved_by", nullable=True, maxlen=100), T("timestamp"),
    ],
    "HEALTH_TREATMENTS": [
        S("health_id", maxlen=64), S("animal_id", maxlen=64),
        S("current_clinical_status", enum=HEALTH_STATUSES), S("farm_name", nullable=True), S("barn_code", nullable=True, maxlen=64),
        S("record_type", enum=RECORD_TYPES), S("administered_by", nullable=True, maxlen=100),
        Field("clinical_payload", "json_object", nullable=True), T("recorded_at"),
    ],
    "VACCINATION_COVERAGE": [
        S("farm_id", maxlen=64), S("farm_name"), S("barn_id", maxlen=64), S("barn_code", maxlen=64),
        I("total_cattle"), I("vaccinated_cattle"), N("coverage_pct"),
    ],
}

# the id that must be unique inside one report (a repeated id means the same fact was sent twice)
UNIQUE_KEY = {
    "OUTBREAK_SURVEILLANCE": "farm_id", "MOVEMENT_TRANSFERS": "transfer_id",
    "HEALTH_TREATMENTS": "health_id", "VACCINATION_COVERAGE": "barn_id",
}

RULES_TEXT = [
    "Envelope: agency, schemaVersion, reportType, farmScope, generatedAt, recordCount and records are all required.",
    f"schemaVersion must be {SCHEMA_JSON} (JSON) or {SCHEMA_XML} (XML); reportType must be one of {', '.join(REPORT_TYPES)}.",
    "farmScope is ALL or a farm id (letters, digits, _ . -, at most 64 characters).",
    "generatedAt is an ISO-8601 timestamp with a time zone, not more than 5 minutes in the future (older than 24 h is a warning).",
    "recordCount must be an integer equal to the number of records; at most 50,000 records; an empty report is accepted with a warning.",
    "Every record must carry every field of its report type with the right type; unknown fields are a warning.",
    "Timestamps in records must be ISO-8601 (a space instead of T is accepted), not in the future and not before 2000-01-01.",
    "The id field of a report type must be unique inside the report (farm_id / transfer_id / health_id / barn_id).",
    "OUTBREAK_SURVEILLANCE: healthy + under_treatment + quarantined <= active_herd_count; vaccinated_any_count <= active_herd_count; "
    "fmd_vaccinated_count and lsd_vaccinated_count <= vaccinated_any_count.",
    "MOVEMENT_TRANSFERS: at least one of origin_farm / origin_barn / destination_farm / destination_barn is present.",
    "HEALTH_TREATMENTS: current_clinical_status is HEALTHY, UNDER_TREATMENT or QUARANTINE; record_type is VACCINATION, TREATMENT, "
    "HEALTH_CHECK or QUARANTINE; clinical_payload is a JSON object; a VACCINATION without a vaccine name is a warning.",
    "VACCINATION_COVERAGE: vaccinated_cattle <= total_cattle; coverage_pct is 0..100 and equals vaccinated/total*100 rounded to 2 decimals (tolerance 0.01).",
    "An identical set of records already received for the same report type and farm is accepted with a DUPLICATE_CONTENT warning.",
]


def spec_document() -> dict[str, Any]:
    return {
        "note": "VinaBeef's own requirements for a surveillance report. Not the official specification of the Department of Animal Health.",
        "schemaVersion": {"json": SCHEMA_JSON, "xml": SCHEMA_XML},
        "reportTypes": list(REPORT_TYPES),
        "limits": {"maxRecords": MAX_RECORDS, "futureToleranceMinutes": 5, "staleAfterHours": 24},
        "recordFields": {rt: [{"name": f.name, "type": f.kind, "nullable": f.nullable, **({"enum": list(f.enum)} if f.enum else {}),
                               **({"min": f.minv} if f.minv is not None else {}), **({"max": f.maxv} if f.maxv is not None else {})}
                              for f in fields] for rt, fields in RECORD_FIELDS.items()},
        "uniqueKey": UNIQUE_KEY,
        "rules": RULES_TEXT,
    }


class Findings:
    """Collects errors and warnings; keeps the first MAX_ERRORS_KEPT of each but counts them all."""

    def __init__(self) -> None:
        self.errors: list[dict] = []
        self.warnings: list[dict] = []
        self.error_count = 0
        self.warning_count = 0

    def error(self, path: str, code: str, message: str) -> None:
        self.error_count += 1
        if len(self.errors) < MAX_ERRORS_KEPT:
            self.errors.append({"path": path, "code": code, "message": message})

    def warn(self, path: str, code: str, message: str) -> None:
        self.warning_count += 1
        if len(self.warnings) < MAX_ERRORS_KEPT:
            self.warnings.append({"path": path, "code": code, "message": message})

    @property
    def ok(self) -> bool:
        return self.error_count == 0


def parse_ts(value: str) -> tuple[datetime | None, bool]:
    """(datetime in UTC, had_timezone). None when the text is not a valid timestamp."""
    m = TS_RE.match(value.strip())
    if not m:
        return None, False
    y, mo, d, h, mi, s, frac, tz = m.groups()
    try:
        micro = int((frac or "0").ljust(6, "0")[:6])
        base = datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), micro)
    except ValueError:
        return None, False
    if tz is None:
        return base.replace(tzinfo=timezone.utc), False
    if tz == "Z":
        return base.replace(tzinfo=timezone.utc), True
    sign = -1 if tz[0] == "-" else 1
    digits = tz[1:].replace(":", "")
    oh, om = int(digits[:2]), int(digits[2:4] or 0)
    if oh > 23 or om > 59:
        return None, False
    return (base - sign * timedelta(hours=oh, minutes=om)).replace(tzinfo=timezone.utc), True


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _coerce(f: Field, v: Any, from_xml: bool) -> tuple[Any, str | None]:
    """Returns (value, problem). XML delivers text for everything, so numeric text is converted first."""
    if v is None or (from_xml and isinstance(v, str) and v == ""):
        return None, None
    if from_xml and isinstance(v, str):
        if f.kind == "int":
            return (int(v), None) if INT_RE.match(v.strip()) else (None, "an integer")
        if f.kind == "num":
            return (float(v), None) if NUM_RE.match(v.strip()) else (None, "a number")
    return v, None


def _check_record(findings: Findings, path: str, rec: Any, fields: list[Field], now: datetime,
                  from_xml: bool, state: dict) -> dict | None:
    if not isinstance(rec, dict):
        findings.error(path, "WRONG_TYPE", "a record must be an object")
        return None
    clean: dict[str, Any] = {}
    names = {f.name for f in fields}
    for key in rec:
        if key not in names:
            state["unknown"].setdefault(key, 0)
            state["unknown"][key] += 1
    for f in fields:
        p = f"{path}.{f.name}"
        if f.name not in rec:
            findings.error(p, "REQUIRED", "field is missing")
            continue
        v, bad = _coerce(f, rec[f.name], from_xml)
        if bad:
            findings.error(p, "WRONG_TYPE", f"must be {bad}")
            continue
        if v is None:
            if f.nullable:
                clean[f.name] = None
            else:
                findings.error(p, "REQUIRED", "must not be null or empty")
            continue
        if f.kind == "str":
            if not isinstance(v, str):
                findings.error(p, "WRONG_TYPE", "must be text")
                continue
            if v.strip() == "":
                if f.nullable:
                    clean[f.name] = None
                else:
                    findings.error(p, "REQUIRED", "must not be empty")
                continue
            if f.maxlen and len(v) > f.maxlen:
                findings.error(p, "TOO_LONG", f"longer than {f.maxlen} characters")
                continue
            if f.enum and v not in f.enum:
                findings.error(p, "NOT_ALLOWED_VALUE", f"'{v[:40]}' is not one of {', '.join(f.enum)}")
                continue
            clean[f.name] = v
        elif f.kind == "int":
            if not _is_int(v):
                findings.error(p, "WRONG_TYPE", "must be an integer")
                continue
            if (f.minv is not None and v < f.minv) or (f.maxv is not None and v > f.maxv):
                findings.error(p, "OUT_OF_RANGE", f"{v} is outside {int(f.minv)}..{int(f.maxv)}")
                continue
            clean[f.name] = v
        elif f.kind == "num":
            if not _is_num(v):
                findings.error(p, "WRONG_TYPE", "must be a number")
                continue
            if (f.minv is not None and v < f.minv) or (f.maxv is not None and v > f.maxv):
                findings.error(p, "OUT_OF_RANGE", f"{v} is outside {f.minv}..{f.maxv}")
                continue
            clean[f.name] = float(v)
        elif f.kind == "ts":
            if not isinstance(v, str):
                findings.error(p, "WRONG_TYPE", "must be a timestamp text")
                continue
            ts, has_tz = parse_ts(v)
            if ts is None:
                findings.error(p, "BAD_TIMESTAMP", f"'{v[:40]}' is not an ISO-8601 timestamp")
                continue
            if ts > now + FUTURE_TOLERANCE:
                findings.error(p, "FUTURE_TIMESTAMP", f"{v} is in the future")
                continue
            if ts < EARLIEST:
                findings.error(p, "OUT_OF_RANGE", f"{v} is before 2000-01-01")
                continue
            if not has_tz:
                state["no_tz"] += 1
            clean[f.name] = v
        elif f.kind == "json_object":
            obj = v
            if isinstance(v, str):
                try:
                    obj = json.loads(v)
                except ValueError:
                    findings.error(p, "INVALID_JSON", "must contain a JSON object")
                    continue
            if not isinstance(obj, dict):
                findings.error(p, "INVALID_JSON", "must contain a JSON object")
                continue
            clean[f.name] = obj
    return clean


def _cross_checks(findings: Findings, report_type: str, i: int, r: dict) -> None:
    path = f"records[{i}]"
    if report_type == "OUTBREAK_SURVEILLANCE":
        herd = r.get("active_herd_count")
        if None in (herd, r.get("healthy_count"), r.get("under_treatment_count"), r.get("quarantined_count")):
            pass
        elif r["healthy_count"] + r["under_treatment_count"] + r["quarantined_count"] > herd:
            findings.error(path, "INCONSISTENT", "healthy + under_treatment + quarantined is larger than active_herd_count")
        vany = r.get("vaccinated_any_count")
        if vany is not None and herd is not None and vany > herd:
            findings.error(f"{path}.vaccinated_any_count", "INCONSISTENT", "larger than active_herd_count")
        for k in ("fmd_vaccinated_count", "lsd_vaccinated_count"):
            if r.get(k) is not None and vany is not None and r[k] > vany:
                findings.error(f"{path}.{k}", "INCONSISTENT", "larger than vaccinated_any_count")
    elif report_type == "MOVEMENT_TRANSFERS":
        if not any(r.get(k) for k in ("origin_farm", "origin_barn", "destination_farm", "destination_barn")):
            findings.error(path, "INCONSISTENT", "a transfer needs at least one origin or destination")
    elif report_type == "HEALTH_TREATMENTS":
        payload = r.get("clinical_payload")
        if r.get("record_type") == "VACCINATION":
            vac = payload.get("vaccine") if isinstance(payload, dict) else None
            if not (isinstance(vac, str) and vac.strip()):
                findings.warn(f"{path}.clinical_payload", "VACCINE_NAME_MISSING",
                              "a vaccination without a vaccine name cannot be reported as FMD / LSD / other")
    elif report_type == "VACCINATION_COVERAGE":
        total, vac, pct = r.get("total_cattle"), r.get("vaccinated_cattle"), r.get("coverage_pct")
        if total is not None and vac is not None:
            if vac > total:
                findings.error(f"{path}.vaccinated_cattle", "INCONSISTENT", "larger than total_cattle")
            elif pct is not None:
                expected = round(vac * 100.0 / total, 2) if total > 0 else 0.0
                if abs(expected - pct) > 0.01 + 1e-9:
                    findings.error(f"{path}.coverage_pct", "INCONSISTENT", f"{pct} does not match {vac}/{total} = {expected}")


@dataclass
class Outcome:
    findings: Findings
    report_type: str | None = None
    farm_scope: str | None = None
    record_count: int = 0
    records: list | None = None


def validate(doc: Any, fmt: str, now: datetime | None = None) -> Outcome:
    """Validates one report. `doc` is the parsed JSON object, or the XML mapped to the same shape (see main.xml_to_doc)."""
    now = now or datetime.now(timezone.utc)
    f = Findings()
    out = Outcome(f)
    from_xml = fmt == "XML"
    if not isinstance(doc, dict):
        f.error("$", "WRONG_TYPE", "the report must be an object")
        return out

    # ---- envelope
    def need(key: str):
        if key not in doc or doc[key] is None or (isinstance(doc[key], str) and not doc[key].strip()):
            f.error(key, "REQUIRED", "field is missing")
            return None
        return doc[key]

    if not from_xml:      # the XML form carries no agency element
        agency = need("agency")
        if agency is not None and agency != AGENCY:
            f.warn("agency", "UNEXPECTED_AGENCY", f"expected '{AGENCY}'")
    schema = need("schemaVersion")
    expected_schema = SCHEMA_XML if from_xml else SCHEMA_JSON
    if schema is not None and schema != expected_schema:
        f.error("schemaVersion", "BAD_SCHEMA_VERSION", f"'{str(schema)[:30]}' is not supported; expected {expected_schema}")
    rt = need("reportType")
    if rt is not None and rt not in REPORT_TYPES:
        f.error("reportType", "UNKNOWN_REPORT_TYPE", f"'{str(rt)[:40]}' is not one of {', '.join(REPORT_TYPES)}")
        rt = None
    out.report_type = rt
    scope = need("farmScope")
    if scope is not None:
        if not isinstance(scope, str) or not FARM_SCOPE_RE.match(scope):
            f.error("farmScope", "BAD_VALUE", "must be ALL or a farm id (letters, digits, _ . -)")
        else:
            out.farm_scope = scope
    gen = need("generatedAt")
    if gen is not None:
        ts, has_tz = parse_ts(gen) if isinstance(gen, str) else (None, False)
        if ts is None:
            f.error("generatedAt", "BAD_TIMESTAMP", "is not an ISO-8601 timestamp")
        else:
            if not has_tz:
                f.error("generatedAt", "BAD_TIMESTAMP", "must carry a time zone (Z or +hh:mm)")
            if ts > now + FUTURE_TOLERANCE:
                f.error("generatedAt", "FUTURE_TIMESTAMP", "is in the future")
            elif now - ts > STALE_AFTER:
                f.warn("generatedAt", "STALE_REPORT", "the report is more than 24 hours old")
    declared = need("recordCount")
    if declared is not None:
        if from_xml and isinstance(declared, str) and INT_RE.match(declared.strip()):
            declared = int(declared)
        if not _is_int(declared) or declared < 0:
            f.error("recordCount", "WRONG_TYPE", "must be a non-negative integer")
            declared = None
    records = doc.get("records")
    if records is None:
        f.error("records", "REQUIRED", "field is missing")
        return out
    if not isinstance(records, list):
        f.error("records", "WRONG_TYPE", "must be a list")
        return out
    out.record_count = len(records)
    out.records = records
    if declared is not None and declared != len(records):
        f.error("recordCount", "COUNT_MISMATCH", f"says {declared} but {len(records)} records were sent")
    if len(records) > MAX_RECORDS:
        f.error("records", "TOO_MANY_RECORDS", f"{len(records)} records; the limit is {MAX_RECORDS}")
        return out
    if not records:
        f.warn("records", "EMPTY_REPORT", "the report has no records")
    known = {"agency", "schemaVersion", "reportType", "farmScope", "generatedAt", "recordCount", "records"}
    for k in doc:
        if k not in known:
            f.warn(k, "UNKNOWN_FIELD", "not part of the report envelope; ignored")
    if rt is None:
        return out

    # ---- records
    fields = RECORD_FIELDS[rt]
    state = {"unknown": {}, "no_tz": 0}
    seen: dict[str, int] = {}
    key = UNIQUE_KEY[rt]
    for i, rec in enumerate(records):
        clean = _check_record(f, f"records[{i}]", rec, fields, now, from_xml, state)
        if clean is None:
            continue
        _cross_checks(f, rt, i, clean)
        kv = clean.get(key)
        if kv is not None:
            if kv in seen:
                f.error(f"records[{i}].{key}", "DUPLICATE_ID", f"'{kv}' already appears in records[{seen[kv]}]")
            else:
                seen[kv] = i
    for k, n in state["unknown"].items():
        f.warn(f"records[*].{k}", "UNKNOWN_FIELD", f"not part of {rt}; ignored ({n} records)")
    if state["no_tz"]:
        f.warn("records[*]", "NO_TIMEZONE", f"{state['no_tz']} timestamps carry no time zone; UTC assumed")
    return out
