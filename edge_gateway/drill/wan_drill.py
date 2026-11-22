"""Live drill of the barn edge gateway against a local MQTT broker and a fake backend (needs: pip install amqtt paho-mqtt httpx).
Run:  python drill/wan_drill.py     Prints PASS/FAIL per scenario; nothing outside a temp folder is touched."""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

import paho.mqtt.client as mqtt

HERE = os.path.dirname(os.path.abspath(__file__)); EDGE = os.path.join(HERE, "..", "barn_edge_gateway.py")
BP, API, EP = 18830, 18080, 18090
tmp = tempfile.mkdtemp(); modef = f"{tmp}/mode"; outf = f"{tmp}/accepted.jsonl"; dbf = f"{tmp}/edge.db"
open(modef, "w").write("ok"); open(outf, "w").close()
procs = {}
ok_all = True
def check(name, cond, extra=""):
    global ok_all; ok_all &= bool(cond); print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if not cond and extra != "" else ""))
def start(name, *cmd, env=None):
    procs[name] = subprocess.Popen(cmd, env={**os.environ, **(env or {})}, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True); return procs[name]
def stop(name, sig=signal.SIGTERM):
    p = procs.pop(name, None)
    if p: p.send_signal(sig); p.wait(10)
def mode(m): open(modef, "w").write(m)
def status():
    try: return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{EP}/status", timeout=3).read())
    except Exception: return None
def wait(cond, secs=25, step=0.3):
    t = time.time()
    while time.time() - t < secs:
        v = cond()
        if v: return v
        time.sleep(step)
    return None
def accepted(): return [json.loads(l) for l in open(outf) if l.strip()]
def start_gateway(clean=True, qos=1):
    start("gw", sys.executable, EDGE, env={"EDGE_DB_PATH": dbf, "CLOUD_BASE_URL": f"http://127.0.0.1:{API}/api/v1", "EDGE_BIND_PORT": str(EP), "EDGE_BIND_HOST": "127.0.0.1",
          "MQTT_URL": f"mqtt://127.0.0.1:{BP}", "MQTT_TOPICS": "Web/475/Monitoring/728589", "MQTT_CLIENT_ID": "Web_drill", "MQTT_CLEAN_SESSION": "true" if clean else "false",
          "MQTT_QOS": str(qos), "MQTT_USERNAME": "728589", "MQTT_PASSWORD": "token/x", "EDGE_BACKOFF_MIN": "0.5", "EDGE_BACKOFF_MAX": "2", "LOG_LEVEL": "INFO"})
pub = None
def publish(key, ts, card="044E6602AD1490"):
    ev = {"EVENT_KEY": key, "TIMESTAMP": ts, "CARD_UUID": card, "READER_NAME": "DrC0F8", "COMPANY_KEY": 475, "NOTES": {"ACTION": "OPEN_DOOR"}}
    pub.publish("Web/475/Monitoring/728589", json.dumps(ev), qos=1).wait_for_publish(5)
try:
    start("broker", sys.executable, os.path.join(HERE, "broker.py"), str(BP)); time.sleep(2)
    start("api", sys.executable, os.path.join(HERE, "fake_backend.py"), str(API), modef, outf); time.sleep(1)
    pub = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION1, client_id="drill_pub"); pub.connect("127.0.0.1", BP); pub.loop_start()
    start_gateway()
    check("gateway comes up, MQTT connected, ONLINE", wait(lambda: (status() or {}).get("mqttConnected") and (status() or {}).get("wan") in ("ONLINE", "UNKNOWN")))
    T = int(time.time() * 1000)

    # ---- A. normal flow
    publish(1001, T + 1000); publish(1002, T + 2000)
    check("A. events flow end to end while everything is up", wait(lambda: len(accepted()) == 2))

    # ---- B. backend/WAN down (connection refused): events keep arriving from MQTT and queue on disk
    stop("api"); time.sleep(0.5)
    for i, k in enumerate((2001, 2002, 2003)): publish(k, T + 10_000 + i * 1000)
    s = wait(lambda: (lambda x: x if x and x["queue"]["pending"] == 3 and x["wan"] == "OFFLINE" else None)(status()))
    check("B. backend unreachable: gateway reports OFFLINE and holds 3 events", s, status())
    check("B. nothing is lost or sent twice while it is down", len(accepted()) == 2)
    # published OUT OF hardware order on purpose
    publish(2000, T + 9_000)
    start("api", sys.executable, os.path.join(HERE, "fake_backend.py"), str(API), modef, outf)
    check("B. link restored: backlog is replayed and the queue empties", wait(lambda: len(accepted()) == 6 and (status() or {}).get("queue", {}).get("pending") == 0, 30), (status(), len(accepted())))
    got = [a["key"] for a in accepted()[2:]]
    check("B. replay is in hardware-time order (2000 first although it arrived last)", got == [2000, 2001, 2002, 2003], got)
    check("B. replay has no duplicates", not any(a["dup"] for a in accepted()))
    check("B. gateway is ONLINE again", wait(lambda: (status() or {}).get("wan") == "ONLINE"))

    # ---- C. backend answers 503 (DB down): health fails first, backlog is not thrown at it
    mode("down503"); publish(3001, T + 20_000)
    check("C. backend up but unhealthy: gateway reports DEGRADED and keeps the event", wait(lambda: (status() or {}).get("wan") == "DEGRADED" and status()["queue"]["pending"] == 1))
    mode("ok")
    check("C. recovered: event delivered", wait(lambda: len(accepted()) == 7 and status()["queue"]["pending"] == 0, 30))

    # ---- D. health ok but writes fail: events are kept, never dropped, and not marked DEAD by a transient 503
    mode("health_ok_write_503"); publish(4001, T + 30_000); time.sleep(4)
    s = status(); check("D. writes failing with 503: event stays pending, not DEAD", s["queue"]["pending"] == 1 and s["queue"]["dead"] == 0, s)
    mode("ok"); check("D. writes recover: delivered", wait(lambda: len(accepted()) == 8))

    # ---- E. slow/hanging WAN (timeouts)
    mode("hang"); publish(5001, T + 40_000)
    check("E. hanging backend: gateway keeps taking events (does not block intake) and shows OFFLINE", wait(lambda: (status() or {}).get("queue", {}).get("pending") == 1 and (status() or {}).get("wan") == "OFFLINE", 30), status())
    mode("ok"); check("E. after the hang clears the event is delivered", wait(lambda: len(accepted()) == 9, 40), len(accepted()))

    # ---- F. gateway power cut during an outage (kill -9): queue survives on disk
    stop("api"); publish(6001, T + 50_000); publish(6002, T + 51_000)
    wait(lambda: (status() or {}).get("queue", {}).get("pending") == 2)
    stop("gw", signal.SIGKILL); time.sleep(1)
    start("api", sys.executable, os.path.join(HERE, "fake_backend.py"), str(API), modef, outf); time.sleep(1)
    start_gateway()
    check("F. after kill -9 and restart the queued events are still there and get delivered", wait(lambda: len(accepted()) == 11 and (status() or {}).get("queue", {}).get("pending") == 0, 30), (status(), len(accepted())))

    # ---- G. same EVENT_KEY twice (broker redelivery, reader resend)
    publish(7001, T + 60_000); publish(7001, T + 60_000)
    check("G. a redelivered EVENT_KEY is counted as a duplicate and sent once", wait(lambda: len(accepted()) == 12 and status()["counters"]["duplicates"] >= 1) and sum(1 for a in accepted() if a["key"] == 7001) == 1, status())

    # ---- H. broker (Safetrust cloud) lost: this is the case the gateway cannot buffer
    stop("broker"); time.sleep(0.5)
    check("H. broker lost: gateway notices (mqttConnected false) but keeps running", wait(lambda: status() and status()["mqttConnected"] is False, 20), status())
    start("broker", sys.executable, os.path.join(HERE, "broker.py"), str(BP)); time.sleep(2)
    check("H. broker back: the gateway reconnects and resubscribes by itself", wait(lambda: status() and status()["mqttConnected"] is True, 40), status())
    try: pub.disconnect()
    except Exception: pass
    pub = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION1, client_id="drill_pub2"); pub.connect("127.0.0.1", BP); pub.loop_start(); time.sleep(1)
    publish(8001, T + 70_000)
    check("H. events published after the reconnect arrive", wait(lambda: len(accepted()) == 13), len(accepted()))
    # ---- I. the GATEWAY is off the broker while the readers keep publishing (barn LAN/WAN to Safetrust cut, readers still online)
    for clean, label, key in ((True, "clean session (default)", 9001), (False, "persistent session (MQTT_CLEAN_SESSION=false)", 9101)):
        stop("gw"); time.sleep(1)
        n0 = len(accepted())
        if not clean:                       # a persistent session must exist BEFORE the outage, so connect once with it first
            start_gateway(clean=False); wait(lambda: (status() or {}).get("mqttConnected")); stop("gw"); time.sleep(1)
        publish(key, T + 90_000 + key); publish(key + 1, T + 91_000 + key)
        start_gateway(clean=clean); time.sleep(8)
        n = len(accepted()) - n0
        if clean:
            check("I. " + label + ": events published while the gateway was away are LOST (documented limit)", n == 0, n)
        else:
            print(f"INFO I. {label}: {n} of 2 events published during the gateway outage were received afterwards "
                  f"(depends on the broker; the local test broker does not queue for offline sessions, so test this on the Safetrust broker)")
finally:
    for n in list(procs): 
        try: stop(n, signal.SIGKILL)
        except Exception: pass
    shutil.rmtree(tmp, ignore_errors=True)
print("\nALL PASS" if ok_all else "\nFAILURES"); sys.exit(0 if ok_all else 1)
