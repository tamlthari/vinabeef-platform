"""Tests of the NADCS test server: validation rules (spec.py) and the HTTP behaviour (main.py). Run: pytest -q"""
import copy
import json
import os
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

os.environ["NADCS_MOCK_API_KEYS"] = "test-key,second-key"
import main  # noqa: E402
import spec  # noqa: E402

KEY = {"X-API-Key": "test-key"}
NOW = datetime(2026, 11, 22, 7, 0, 0, tzinfo=timezone.utc)
GEN = "2026-11-22T06:59:00.123456Z"


def envelope(rt, records, **kw):
    d = {"agency": spec.AGENCY, "schemaVersion": "NADCS-2.1", "reportType": rt, "farmScope": "ALL", "generatedAt": GEN,
         "recordCount": len(records), "records": records}
    d.update(kw)
    return d


OUTBREAK = {"farm_id": "FARM-DAK-LAK", "farm_name": "Dak Lak Farm", "location": "Buon Ma Thuot", "barn_count": 4, "active_herd_count": 100,
            "healthy_count": 90, "under_treatment_count": 5, "quarantined_count": 2, "vaccinated_any_count": 80, "fmd_vaccinated_count": 70, "lsd_vaccinated_count": 30}
TRANSFER = {"transfer_id": "TR-1", "animal_id": "VB-000001", "transfer_type": "INTER_BARN", "origin_farm": "Dak Lak", "origin_barn": "DL-01",
            "destination_farm": "Dak Lak", "destination_barn": "DL-02", "reason": "Rotation", "approved_by": "manager_daklak", "timestamp": "2026-11-21 08:10:00.123+00"}
HEALTH = {"health_id": "H-1", "animal_id": "VB-000001", "current_clinical_status": "HEALTHY", "farm_name": "Dak Lak", "barn_code": "DL-01",
          "record_type": "VACCINATION", "administered_by": "vet1", "clinical_payload": '{"vaccine": "FMD", "batch": "B7"}', "recorded_at": "2026-11-20 03:00:00+07"}
COVER = {"farm_id": "FARM-DAK-LAK", "farm_name": "Dak Lak", "barn_id": "BARN-DL-01", "barn_code": "DL-01", "total_cattle": 200, "vaccinated_cattle": 157, "coverage_pct": 78.5}
SAMPLE = {"OUTBREAK_SURVEILLANCE": OUTBREAK, "MOVEMENT_TRANSFERS": TRANSFER, "HEALTH_TREATMENTS": HEALTH, "VACCINATION_COVERAGE": COVER}


def codes(out):
    return sorted({e["code"] for e in out.findings.errors})


def check(rt, record, **kw):
    return spec.validate(envelope(rt, [record], **kw), "JSON", NOW)


@pytest.mark.parametrize("rt", spec.REPORT_TYPES)
def test_a_correct_report_of_each_type_is_valid(rt):
    out = check(rt, SAMPLE[rt])
    assert out.findings.ok, out.findings.errors
    assert out.findings.warning_count == 0, out.findings.warnings


def test_envelope_rules():
    base = envelope("VACCINATION_COVERAGE", [COVER])
    for key in ("agency", "schemaVersion", "reportType", "farmScope", "generatedAt", "recordCount", "records"):
        d = copy.deepcopy(base); d.pop(key)
        out = spec.validate(d, "JSON", NOW)
        assert not out.findings.ok and any(e["path"] == key and e["code"] == "REQUIRED" for e in out.findings.errors), key
    assert "BAD_SCHEMA_VERSION" in codes(spec.validate({**base, "schemaVersion": "NADCS-1.0"}, "JSON", NOW))
    assert "UNKNOWN_REPORT_TYPE" in codes(spec.validate({**base, "reportType": "NOPE"}, "JSON", NOW))
    assert "BAD_VALUE" in codes(spec.validate({**base, "farmScope": "x y; drop"}, "JSON", NOW))
    assert "COUNT_MISMATCH" in codes(spec.validate({**base, "recordCount": 5}, "JSON", NOW))
    assert "WRONG_TYPE" in codes(spec.validate({**base, "recordCount": "1"}, "JSON", NOW))
    assert "WRONG_TYPE" in codes(spec.validate({**base, "records": "nope"}, "JSON", NOW))
    assert "WRONG_TYPE" in codes(spec.validate([], "JSON", NOW))


def test_generated_at_rules():
    base = envelope("VACCINATION_COVERAGE", [COVER])
    assert "BAD_TIMESTAMP" in codes(spec.validate({**base, "generatedAt": "yesterday"}, "JSON", NOW))
    assert "BAD_TIMESTAMP" in codes(spec.validate({**base, "generatedAt": "2026-11-22T06:00:00"}, "JSON", NOW)), "no time zone"
    assert "FUTURE_TIMESTAMP" in codes(spec.validate({**base, "generatedAt": "2026-11-22T07:10:00Z"}, "JSON", NOW))
    assert spec.validate({**base, "generatedAt": "2026-11-22T07:04:00Z"}, "JSON", NOW).findings.ok, "within the 5 minute skew"
    stale = spec.validate({**base, "generatedAt": "2026-11-20T00:00:00Z"}, "JSON", NOW)
    assert stale.findings.ok and any(w["code"] == "STALE_REPORT" for w in stale.findings.warnings)


def test_empty_report_is_accepted_with_a_warning_and_unknown_fields_warn():
    out = spec.validate(envelope("MOVEMENT_TRANSFERS", [], extra="x"), "JSON", NOW)
    assert out.findings.ok
    assert {w["code"] for w in out.findings.warnings} == {"EMPTY_REPORT", "UNKNOWN_FIELD"}


def test_too_many_records():
    out = spec.validate(envelope("VACCINATION_COVERAGE", [dict(COVER, barn_id=f"B{i}") for i in range(spec.MAX_RECORDS + 1)]), "JSON", NOW)
    assert "TOO_MANY_RECORDS" in codes(out)


@pytest.mark.parametrize("rt", spec.REPORT_TYPES)
def test_every_field_is_required_in_every_record(rt):
    for f in spec.RECORD_FIELDS[rt]:
        rec = {k: v for k, v in SAMPLE[rt].items() if k != f.name}
        out = check(rt, rec)
        assert any(e["path"] == f"records[0].{f.name}" and e["code"] == "REQUIRED" for e in out.findings.errors), f.name


def test_nullable_fields_accept_null_and_the_others_do_not():
    assert check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, location=None)).findings.ok
    assert check("MOVEMENT_TRANSFERS", dict(TRANSFER, reason=None, approved_by=None)).findings.ok
    assert "REQUIRED" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, farm_name=None)))
    assert "REQUIRED" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, farm_name="  ")))


def test_numbers_must_be_numbers_and_in_range():
    assert "WRONG_TYPE" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, barn_count="4")))
    assert "WRONG_TYPE" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, barn_count=True)))
    assert "WRONG_TYPE" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, barn_count=4.5)))
    assert "OUT_OF_RANGE" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, barn_count=-1)))
    assert "OUT_OF_RANGE" in codes(check("VACCINATION_COVERAGE", dict(COVER, coverage_pct=101.0)))


def test_outbreak_consistency():
    assert "INCONSISTENT" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, healthy_count=100)))        # 100+5+2 > 100
    assert "INCONSISTENT" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, vaccinated_any_count=101, fmd_vaccinated_count=0, lsd_vaccinated_count=0)))
    assert "INCONSISTENT" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, fmd_vaccinated_count=81)))
    assert "INCONSISTENT" in codes(check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, lsd_vaccinated_count=81)))
    assert check("OUTBREAK_SURVEILLANCE", dict(OUTBREAK, healthy_count=0, under_treatment_count=0, quarantined_count=0)).findings.ok, "unclassified animals are allowed"


def test_coverage_consistency():
    assert "INCONSISTENT" in codes(check("VACCINATION_COVERAGE", dict(COVER, vaccinated_cattle=201)))
    assert "INCONSISTENT" in codes(check("VACCINATION_COVERAGE", dict(COVER, coverage_pct=80.0)))
    assert check("VACCINATION_COVERAGE", dict(COVER, coverage_pct=78.51)).findings.ok, "tolerance 0.01"
    assert check("VACCINATION_COVERAGE", dict(COVER, total_cattle=0, vaccinated_cattle=0, coverage_pct=0.0)).findings.ok


def test_transfer_needs_an_origin_or_destination():
    none = dict(TRANSFER, origin_farm=None, origin_barn=None, destination_farm=None, destination_barn=None)
    assert "INCONSISTENT" in codes(check("MOVEMENT_TRANSFERS", none))
    assert check("MOVEMENT_TRANSFERS", dict(none, destination_farm="Gia Lai")).findings.ok


def test_health_rules():
    assert "NOT_ALLOWED_VALUE" in codes(check("HEALTH_TREATMENTS", dict(HEALTH, current_clinical_status="SICK")))
    assert "NOT_ALLOWED_VALUE" in codes(check("HEALTH_TREATMENTS", dict(HEALTH, record_type="SURGERY")))
    assert "INVALID_JSON" in codes(check("HEALTH_TREATMENTS", dict(HEALTH, clinical_payload="{not json")))
    assert "INVALID_JSON" in codes(check("HEALTH_TREATMENTS", dict(HEALTH, clinical_payload="[1,2]")))
    assert check("HEALTH_TREATMENTS", dict(HEALTH, clinical_payload={"vaccine": "LSD"})).findings.ok, "an object is accepted too"
    nameless = check("HEALTH_TREATMENTS", dict(HEALTH, clinical_payload="{}"))
    assert nameless.findings.ok and nameless.findings.warnings[0]["code"] == "VACCINE_NAME_MISSING"
    assert check("HEALTH_TREATMENTS", dict(HEALTH, record_type="TREATMENT", clinical_payload="{}")).findings.warning_count == 0


def test_timestamps_in_records():
    for good in ("2026-11-21 08:10:00+00", "2026-11-21T08:10:00Z", "2026-11-21 08:10:00.5+07:00", "2026-11-21 08:10:00-0530"):
        assert check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp=good)).findings.ok, good
    for bad in ("21/11/2026", "2026-13-01 00:00:00+00", "2026-11-21", "2026-11-21 08:10:00+25"):
        assert "BAD_TIMESTAMP" in codes(check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp=bad))), bad
    assert "FUTURE_TIMESTAMP" in codes(check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp="2026-11-23 00:00:00+00")))
    assert "OUT_OF_RANGE" in codes(check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp="1999-12-31 23:59:59+00")))
    assert "FUTURE_TIMESTAMP" in codes(check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp="2026-11-22 14:30:00+07")))        # 07:30Z > 07:05Z
    no_tz = check("MOVEMENT_TRANSFERS", dict(TRANSFER, timestamp="2026-11-21 08:10:00"))
    assert no_tz.findings.ok and no_tz.findings.warnings[0]["code"] == "NO_TIMEZONE"


def test_duplicate_ids_and_many_errors_are_counted():
    out = spec.validate(envelope("VACCINATION_COVERAGE", [COVER, COVER]), "JSON", NOW)
    assert "DUPLICATE_ID" in codes(out)
    bad = [{} for _ in range(100)]
    out = spec.validate(envelope("VACCINATION_COVERAGE", bad), "JSON", NOW)
    assert out.findings.error_count == 700 and len(out.findings.errors) == spec.MAX_ERRORS_KEPT


# ------------------------------------------------------------------------------------------------------------ HTTP
@pytest.fixture()
def client():
    main.SUBMISSIONS.clear(); main.SEEN_CONTENT.clear(); main.STATE["behavior"] = "normal"
    return TestClient(main.app)


def good(rt="VACCINATION_COVERAGE"):
    return envelope(rt, [copy.deepcopy(SAMPLE[rt])], generatedAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))


def post(client, body, headers=KEY, ct="application/json"):
    data = body if isinstance(body, (bytes, str)) else json.dumps(body)
    return client.post("/v2/surveillance/herd-health", content=data, headers={**headers, "Content-Type": ct})


def test_health_index_and_spec_need_no_key(client):
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/").json()["mock"] is True
    s = client.get("/v2/surveillance/spec").json()
    assert s["reportTypes"] == list(spec.REPORT_TYPES) and "Not the official" in s["note"] and s["rules"]


def test_authentication(client):
    assert post(client, good(), headers={}).status_code == 401
    assert post(client, good(), headers={"X-API-Key": "wrong"}).status_code == 401
    assert post(client, good(), headers={"Authorization": "Bearer second-key"}).status_code == 202
    assert client.get("/v2/surveillance/submissions").status_code == 401
    assert client.get("/admin/behavior").status_code == 401
    assert client.post("/admin/behavior", json={"mode": "normal"}).status_code == 401


def test_no_keys_configured_refuses_everything(client, monkeypatch):
    monkeypatch.setenv("NADCS_MOCK_API_KEYS", "")
    r = post(client, good())
    assert r.status_code == 503 and "not configured" in r.json()["summary"]


@pytest.mark.parametrize("rt", spec.REPORT_TYPES)
def test_accepts_a_valid_report_of_each_type_with_a_receipt(client, rt):
    r = post(client, good(rt))
    assert r.status_code == 202, r.text
    b = r.json()
    assert b["status"] == "ACCEPTED" and b["mock"] is True and b["receiptId"].startswith("NADCS-MOCK-") and b["recordCount"] == 1
    assert b["summary"].startswith("Accepted: receipt NADCS-MOCK-")
    assert r.headers["X-Mock-Server"] == "nadcs-mock"


def test_rejects_an_invalid_report_with_precise_errors(client):
    bad = good(); bad["records"][0]["coverage_pct"] = 99.0; bad["recordCount"] = 3
    r = post(client, bad)
    assert r.status_code == 422
    b = r.json()
    assert b["status"] == "REJECTED" and b["errorCount"] == 2 and b["summary"].startswith("Rejected: 2 validation errors.")
    assert {(e["path"], e["code"]) for e in b["errors"]} == {("recordCount", "COUNT_MISMATCH"), ("records[0].coverage_pct", "INCONSISTENT")}
    assert not any(s["outcome"] == "ACCEPTED" for s in main.SUBMISSIONS)


def test_unreadable_and_unsupported_bodies(client):
    assert post(client, "{not json").status_code == 400
    assert post(client, b"\xff\xfe").status_code == 400
    r = post(client, "hello", ct="text/plain")
    assert r.status_code == 415
    assert post(client, "<a><b></a>", ct="application/xml").status_code == 400
    assert post(client, "<Other/>", ct="application/xml").status_code == 400


def test_xml_reports(client):
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<SurveillanceReport schema="NADCS-WOAH-2.1" reportType="VACCINATION_COVERAGE" farmScope="ALL" '
           f'generatedAt="{datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}">\n  <RecordCount>1</RecordCount>\n  <Records>\n    <Record>\n'
           '      <farm_id>FARM-DAK-LAK</farm_id>\n      <farm_name>Dak Lak &amp; Co</farm_name>\n      <barn_id>BARN-DL-01</barn_id>\n      <barn_code>DL-01</barn_code>\n'
           '      <total_cattle>200</total_cattle>\n      <vaccinated_cattle>157</vaccinated_cattle>\n      <coverage_pct>78.50</coverage_pct>\n    </Record>\n  </Records>\n</SurveillanceReport>\n')
    r = post(client, xml, ct="application/xml")
    assert r.status_code == 202, r.text
    r = post(client, xml.replace("<total_cattle>200<", "<total_cattle>many<"), ct="application/xml")
    assert r.status_code == 422 and any(e["code"] == "WRONG_TYPE" for e in r.json()["errors"])
    r = post(client, xml.replace('schema="NADCS-WOAH-2.1"', 'schema="NADCS-2.1"'), ct="application/xml")
    assert r.status_code == 422 and r.json()["errors"][0]["code"] == "BAD_SCHEMA_VERSION"


def test_xml_entity_attack_is_refused(client):
    evil = '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;&a;">]><SurveillanceReport schema="x">&b;</SurveillanceReport>'
    assert post(client, evil, ct="application/xml").status_code == 400


def test_duplicate_content_is_accepted_with_a_warning(client):
    first = post(client, good()).json()
    second = post(client, good()).json()          # new generatedAt, same records
    assert second["status"] == "ACCEPTED" and second["receiptId"] != first["receiptId"]
    assert second["warnings"][0]["code"] == "DUPLICATE_CONTENT" and first["receiptId"] in second["warnings"][0]["message"]


def test_submissions_can_be_listed_and_read(client):
    ok = post(client, good()).json()
    bad = good(); bad["records"][0]["total_cattle"] = -5
    rej = post(client, bad).json()
    listing = client.get("/v2/surveillance/submissions", headers=KEY).json()
    assert listing["count"] == 2 and [i["outcome"] for i in listing["items"]] == ["REJECTED", "ACCEPTED"]
    assert client.get("/v2/surveillance/submissions?outcome=accepted", headers=KEY).json()["count"] == 1
    one = client.get(f"/v2/surveillance/submissions/{rej['submissionId']}", headers=KEY).json()
    assert one["outcome"] == "REJECTED" and one["errors"] and "total_cattle" in one["payloadPreview"]
    assert client.get(f"/v2/surveillance/submissions/{ok['receiptId']}", headers=KEY).json()["outcome"] == "ACCEPTED"
    assert client.get("/v2/surveillance/submissions/SUB-NOPE", headers=KEY).status_code == 404


def test_validate_endpoint_is_a_dry_run(client):
    r = client.post("/v2/surveillance/validate", content=json.dumps(good()), headers={**KEY, "Content-Type": "application/json"})
    assert r.status_code == 200 and r.json()["status"] == "VALID"
    bad = good(); bad["records"][0]["vaccinated_cattle"] = 999
    r = client.post("/v2/surveillance/validate", content=json.dumps(bad), headers={**KEY, "Content-Type": "application/json"})
    assert r.status_code == 422 and r.json()["status"] == "INVALID"
    assert len(main.SUBMISSIONS) == 0, "a dry run records nothing"


def test_behaviors_simulate_an_unhealthy_agency(client):
    def mode(m, **kw):
        return client.post("/admin/behavior", json={"mode": m, **kw}, headers=KEY)
    assert mode("bogus").status_code == 400
    assert mode("server_error").status_code == 200 and post(client, good()).status_code == 500
    assert mode("unavailable").status_code == 200
    r = post(client, good()); assert r.status_code == 503 and r.headers["Retry-After"] == "120"
    mode("rate_limit"); r = post(client, good()); assert r.status_code == 429 and r.headers["Retry-After"] == "60"
    mode("reject_all"); r = post(client, good())
    assert r.status_code == 422 and r.json()["status"] == "REJECTED" and r.json()["summary"].startswith("Rejected")
    mode("slow", slowSeconds=0); assert post(client, good()).status_code == 202
    mode("normal"); assert post(client, good()).status_code == 202
    assert post(client, good(), headers={"X-API-Key": "wrong"}).status_code == 401
    mode("server_error"); assert post(client, good(), headers={"X-API-Key": "wrong"}).status_code == 401, "auth is checked before the behavior"


def test_oversized_body_is_refused(client, monkeypatch):
    monkeypatch.setattr(main, "MAX_BODY_BYTES", 100)
    assert post(client, good()).status_code == 413


# ---------------------------------------------------------------------------------- what the submissions page shows
def _items(client, **params):
    return client.get("/v2/surveillance/submissions", params={"limit": 200, **params}, headers=KEY).json()


def test_every_request_that_reaches_the_endpoint_is_recorded_with_its_outcome(client, monkeypatch):
    post(client, good())                                                              # accepted
    post(client, good("OUTBREAK_SURVEILLANCE") | {"recordCount": 9})                  # rejected by validation
    post(client, b"{not json", ct="application/json")                                 # unreadable
    post(client, "x", ct="text/plain")                                                # unsupported type
    monkeypatch.setattr(main, "MAX_BODY_BYTES", 10); post(client, good()); monkeypatch.setattr(main, "MAX_BODY_BYTES", 30 * 1024 * 1024)
    for m in ("server_error", "unavailable", "rate_limit"):
        client.post("/admin/behavior", json={"mode": m}, headers=KEY); post(client, good())
    client.post("/admin/behavior", json={"mode": "reject_all"}, headers=KEY); post(client, good())
    client.post("/admin/behavior", json={"mode": "normal"}, headers=KEY)
    got = _items(client)
    by_status = sorted((i["httpStatus"], i["outcome"]) for i in got["items"])
    assert by_status == sorted([(202, "ACCEPTED"), (422, "REJECTED"), (400, "REJECTED"), (415, "REJECTED"), (413, "REJECTED"),
                                (500, "ERROR"), (503, "ERROR"), (429, "ERROR"), (422, "REJECTED")])
    assert got["counts"] == {"ACCEPTED": 1, "REJECTED": 5, "ERROR": 3} and got["kept"] == 9 and got["maxKept"] == 200
    for i in got["items"]:
        assert i["submissionId"].startswith("SUB-") and i["summary"], i
        d = client.get(f"/v2/surveillance/submissions/{i['submissionId']}", headers=KEY).json()
        assert d["errors"] or d["outcome"] == "ACCEPTED", d
    assert not any(i["receiptId"] for i in got["items"] if i["outcome"] != "ACCEPTED")


def test_a_wrong_key_is_never_recorded(client):
    post(client, good(), headers={"X-API-Key": "wrong"})
    assert _items(client)["kept"] == 0


def test_the_received_body_is_kept_with_a_hash_of_exactly_the_bytes_sent(client):
    import hashlib
    body = json.dumps(good(), ensure_ascii=False).replace("Dak Lak", "Đắk Lắk 🐄")
    raw = body.encode("utf-8")
    r = post(client, raw, headers={**KEY, "X-Client-Reference": "TX-AB12CD34"})
    sid = r.json()["submissionId"]
    d = client.get(f"/v2/surveillance/submissions/{sid}", headers=KEY).json()
    assert d["sha256"] == hashlib.sha256(raw).hexdigest() and d["bytes"] == len(raw)
    assert d["payload"] == body and "Đắk Lắk 🐄" in d["payload"] and d["payloadTruncated"] is False
    assert d["clientRef"] == "TX-AB12CD34" and d["contentType"] == "application/json" and d["format"] == "JSON"
    assert d["payloadPreview"] == body[:main.PREVIEW_CHARS]


def test_a_body_larger_than_the_store_is_cut_cleanly_but_size_and_hash_stay_whole(client, monkeypatch):
    import hashlib
    monkeypatch.setattr(main, "BODY_STORE_BYTES", 1001)
    body = json.dumps({"pad": "đ" * 3000})                    # 2-byte characters: byte 1001 falls inside a character
    raw = body.encode("utf-8")
    sid = post(client, raw).json()["submissionId"]
    d = client.get(f"/v2/surveillance/submissions/{sid}", headers=KEY).json()
    assert d["payloadTruncated"] is True and d["bytes"] == len(raw) and d["sha256"] == hashlib.sha256(raw).hexdigest()
    assert "�" not in d["payload"] and d["payloadStoredBytes"] <= 1001 and body.startswith(d["payload"])


def test_xml_bodies_are_kept_as_received(client):
    xml = ('<?xml version="1.0" encoding="UTF-8"?><SurveillanceReport schema="NADCS-WOAH-2.1" reportType="VACCINATION_COVERAGE" farmScope="ALL" '
           f'generatedAt="{datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}"><RecordCount>1</RecordCount><Records><Record>'
           + "".join(f"<{k}>{v}</{k}>" for k, v in COVER.items()) + "</Record></Records></SurveillanceReport>")
    sid = post(client, xml, ct="application/xml").json()["submissionId"]
    d = client.get(f"/v2/surveillance/submissions/{sid}", headers=KEY).json()
    assert d["format"] == "XML" and d["payload"] == xml and d["contentType"] == "application/xml"


def test_the_senders_reference_finds_the_submission_and_only_plain_tokens_are_kept(client):
    sid = post(client, good(), headers={**KEY, "X-Client-Reference": "TX-0001"}).json()["submissionId"]
    assert client.get("/v2/surveillance/submissions/TX-0001", headers=KEY).json()["submissionId"] == sid
    for bad in ("x" * 65, "<script>alert(1)</script>", "a b", "TX-1;drop", ""):
        r = post(client, good("OUTBREAK_SURVEILLANCE"), headers={**KEY, "X-Client-Reference": bad})
        assert client.get(f"/v2/surveillance/submissions/{r.json()['submissionId']}", headers=KEY).json()["clientRef"] is None, bad
    newest = post(client, good(), headers={**KEY, "X-Client-Reference": "TX-0001"}).json()["submissionId"]
    assert client.get("/v2/surveillance/submissions/TX-0001", headers=KEY).json()["submissionId"] == newest, "the newest match wins"


def test_an_injected_fault_keeps_the_body_and_the_reference(client):
    client.post("/admin/behavior", json={"mode": "server_error"}, headers=KEY)
    r = post(client, good(), headers={**KEY, "X-Client-Reference": "TX-FAULT1"})
    assert r.status_code == 500 and r.json()["submissionId"].startswith("SUB-")
    d = client.get("/v2/surveillance/submissions/TX-FAULT1", headers=KEY).json()
    assert d["outcome"] == "ERROR" and d["httpStatus"] == 500 and d["errors"][0]["code"] == "INJECTED_FAULT" and d["payload"]


def test_the_list_can_be_filtered_and_reports_the_mode(client):
    post(client, good()); post(client, good("OUTBREAK_SURVEILLANCE") | {"recordCount": 9})
    assert [i["outcome"] for i in _items(client, outcome="rejected")["items"]] == ["REJECTED"]
    client.post("/admin/behavior", json={"mode": "reject_all"}, headers=KEY)
    assert _items(client)["behavior"] == "reject_all"


# ------------------------------------------------------------------------------------------------------- the page
def test_the_page_is_public_carries_no_data_and_no_key_and_has_a_strict_policy(client):
    made = post(client, good(), headers={**KEY, "X-Client-Reference": "TX-PAGE0001"}).json()
    r = client.get("/ui")                                                         # no key sent
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/html")
    for secret in ("test-key", "second-key", made["receiptId"], made["submissionId"], "TX-PAGE0001"):
        assert secret not in r.text, secret
    csp = r.headers["content-security-policy"]
    nonce = csp.split("script-src 'nonce-")[1].split("'")[0]
    assert f'<script nonce="{nonce}">' in r.text and f'<style nonce="{nonce}">' in r.text and "__NONCE__" not in r.text
    assert "default-src 'none'" in csp and "connect-src 'self'" in csp and "unsafe-inline" not in csp and "frame-ancestors 'none'" in csp
    assert r.headers["cache-control"] == "no-store" and r.headers["x-content-type-options"] == "nosniff"
    assert client.get("/ui").headers["content-security-policy"] != csp, "a fresh nonce for every load"
    import re
    assert not re.search(r"""(src|href)=["']?(https?:)?//|url\(|@import""", r.text), "the page must load nothing from outside"


def test_the_page_never_builds_markup_from_received_text():
    import ui
    html = ui.page("N")
    assert "innerHTML" not in html and "outerHTML" not in html and "insertAdjacentHTML" not in html and "document.write" not in html
    assert " style=\"" not in html.split("<script")[0].split("</style>")[1], "inline style attributes would be blocked by the policy"
    assert "onclick=" not in html and "onerror=" not in html


def test_the_page_is_listed_on_the_index(client):
    assert any("/ui" in e for e in client.get("/").json()["endpoints"])
