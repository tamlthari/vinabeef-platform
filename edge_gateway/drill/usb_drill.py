"""Live drill of the USB path of the barn gateway: a real gateway process, a real MQTT broker and a fake backend
(needs: pip install amqtt paho-mqtt httpx).   Run:  python drill/usb_drill.py
The USB reader is played by the gateway's stdin source ("38" + Enter, as a keyboard-style reader would type) and by POST /local-swipe.
Prints PASS/FAIL per scenario; nothing outside a temp folder is touched."""
import json, os, signal, subprocess, sys, time, shutil, tempfile, urllib.request, urllib.error
import paho.mqtt.client as mqtt
HERE = os.path.dirname(os.path.abspath(__file__)); EDGE = os.path.join(HERE, "..", "barn_edge_gateway.py")
BP, API, EP = 18930, 18180, 18190
tmp = tempfile.mkdtemp(); modef = f"{tmp}/mode"; outf = f"{tmp}/accepted.jsonl"; dbf = f"{tmp}/edge.db"
open(modef, "w").write("ok"); open(outf, "w").close()
procs, ok_all = {}, True
def check(name, cond, extra=""):
    global ok_all; ok_all &= bool(cond); print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if not cond and extra != "" else ""))
def start(name, *cmd, env=None, stdin=None):
    procs[name] = subprocess.Popen(cmd, env={**os.environ, **(env or {})}, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True); return procs[name]
def stop(name, sig=signal.SIGTERM):
    p = procs.pop(name, None)
    if p:
        p.send_signal(sig); p.wait(10)
def mode(m): open(modef, "w").write(m)
def status():
    try: return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{EP}/status", timeout=3).read())
    except Exception: return None
def wait(cond, secs=25, step=0.2):
    t = time.time()
    while time.time() - t < secs:
        v = cond()
        if v: return v
        time.sleep(step)
    return None
def accepted(): return [json.loads(l) for l in open(outf) if l.strip()]
def usb_rows(): return [a for a in accepted() if a["src"] == "LOCAL_USB"]
def now(): return int(time.time() * 1000)
def start_gateway(hold_ms=1500):
    start("gw", sys.executable, EDGE, stdin=subprocess.PIPE, env={
        "EDGE_DB_PATH": dbf, "CLOUD_BASE_URL": f"http://127.0.0.1:{API}/api/v1", "EDGE_BIND_PORT": str(EP), "EDGE_BIND_HOST": "127.0.0.1",
        "MQTT_URL": f"mqtt://127.0.0.1:{BP}", "MQTT_TOPICS": "Web/475/Monitoring/728589", "MQTT_CLIENT_ID": "Web_usb_drill", "MQTT_QOS": "1",
        "MQTT_USERNAME": "728589", "MQTT_PASSWORD": "token/x", "EDGE_BACKOFF_MIN": "0.5", "EDGE_BACKOFF_MAX": "2", "LOG_LEVEL": "INFO",
        "LOCAL_INPUTS": "stdin=DrC0F8", "LOCAL_READERS": "DrC0F8,DrC1C4", "LOCAL_HOLD_MS": str(hold_ms), "EDGE_GATEWAY_NO": "3"})
def typed(text):                                      # what a keyboard-style USB reader does: digits, then Enter
    procs["gw"].stdin.write(text + "\n"); procs["gw"].stdin.flush()
def post(path, body):
    req = urllib.request.Request(f"http://127.0.0.1:{EP}{path}", data=json.dumps(body).encode(), method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=5); return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)
pub = None
def reader_event(key, credential, reader="DrC0F8", hw=None):
    ev = {"EVENT_KEY": key, "TIMESTAMP": hw or now(), "CARD_UUID": "044E6602AD1490", "CREDENTIAL_IDENTIFIER": credential, "READER_NAME": reader, "COMPANY_KEY": 475}
    pub.publish("Web/475/Monitoring/728589", json.dumps(ev), qos=1).wait_for_publish(5)
def local(): return (status() or {}).get("local", {})
try:
    start("broker", sys.executable, os.path.join(HERE, "broker.py"), str(BP)); time.sleep(2)
    start("api", sys.executable, os.path.join(HERE, "fake_backend.py"), str(API), modef, outf); time.sleep(1)
    pub = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION1, client_id="usb_drill_pub"); pub.connect("127.0.0.1", BP); pub.loop_start()
    start_gateway()
    check("gateway comes up, MQTT connected, USB input open", wait(lambda: (status() or {}).get("mqttConnected") and any(i["connected"] for i in local().get("inputs", []))), status())

    # ---- U1. the reader's own copy arrives during the hold: the USB swipe is a shadow and never leaves the barn
    typed("38"); check("U1. the swipe is HELD while MQTT is up (not sent)", wait(lambda: local().get("held") == 1) and not usb_rows(), local())
    reader_event(1001, 38)
    check("U1. the reader's copy arrives over MQTT: swipe becomes a shadow", wait(lambda: local().get("shadow") == 1 and local().get("held") == 0), local())
    check("U1. only the reader's own event reaches the backend", wait(lambda: len(accepted()) == 1) and accepted()[0]["key"] == 1001 and not usb_rows(), accepted())
    time.sleep(2.5)
    check("U1. nothing from the USB swipe is sent after the hold would have ended", len(accepted()) == 1 and not usb_rows(), accepted())

    # ---- U2. no copy (the reader never reported it): the hold ends and the swipe is sent with its origin
    t0 = time.time(); typed("1005")
    check("U2. swipe with no reader copy is delivered after the hold", wait(lambda: len(usb_rows()) == 1, 10), accepted())
    row = usb_rows()[0] if usb_rows() else {}
    check("U2. it carries a negative key in gateway 3's space, the USB device, the reader and the credential",
          row.get("key", 0) < -3 * 10**13 and row.get("dev") == "stdin" and row.get("reader") == "DrC0F8" and row.get("cred") == "1005", row)
    check("U2. it waited for the hold (about 1.5 s), not delivered at once", time.time() - t0 >= 1.2, time.time() - t0)

    # ---- U3. swipe over HTTP (a LAN kiosk or bridge), and what is refused
    code, body = post("/local-swipe", {"reader": "DrC1C4", "credential": "0077", "device": "kiosk-1"})
    check("U3. POST /local-swipe is accepted and HELD", code == 202 and body["state"] == "HELD" and body["eventKey"] < 0, (code, body))
    check("U3. it is delivered after the hold with the device name given", wait(lambda: any(a["dev"] == "kiosk-1" and a["cred"] == "77" for a in usb_rows()), 10), accepted())
    check("U3. a credential that is not a number is refused with 400", post("/local-swipe", {"reader": "DrC0F8", "credential": "12ab"})[0] == 400)
    check("U3. a reader this gateway does not serve is refused with 400", post("/local-swipe", {"reader": "DrNope", "credential": "5"})[0] == 400)
    typed("not-a-number")
    check("U3. text typed on the USB input that is not a number is rejected and counted, never sent", wait(lambda: (status() or {}).get("counters", {}).get("localRejected", 0) >= 3), status())

    # ---- U4. backend unreachable: USB swipes wait on disk like any other event
    n = len(accepted()); stop("api"); time.sleep(0.5)
    typed("38"); typed("1005")
    check("U4. backend down: both swipes released and kept in the queue", wait(lambda: (status() or {}).get("queue", {}).get("pending") == 2 and (status() or {}).get("wan") == "OFFLINE", 15), status())
    start("api", sys.executable, os.path.join(HERE, "fake_backend.py"), str(API), modef, outf)
    check("U4. backend back: both delivered, once each", wait(lambda: len(accepted()) == n + 2 and status()["queue"]["pending"] == 0, 30) and not any(a["dup"] for a in accepted()), accepted())

    # ---- U5. losing MQTT (the Safetrust broker): no reader copy can arrive, so waiting stops at once
    stop("gw"); time.sleep(1); start_gateway(hold_ms=20_000)
    wait(lambda: (status() or {}).get("mqttConnected")); n = len(usb_rows())
    typed("38"); check("U5. with a 20 s hold the swipe is held", wait(lambda: local().get("held") == 1), local())
    t0 = time.time(); stop("broker")
    check("U5. the broker is lost: the held swipe is released at once, long before its 20 s hold", wait(lambda: len(usb_rows()) == n + 1, 10) and time.time() - t0 < 10, (time.time() - t0, accepted()[-1:]))
    check("U5. with MQTT down a new swipe is queued immediately, not held", post("/local-swipe", {"reader": "DrC0F8", "credential": "38"})[1].get("state") == "QUEUED")
    check("U5. ...and delivered without waiting", wait(lambda: len(usb_rows()) == n + 2, 5), accepted()[-2:])
    start("broker", sys.executable, os.path.join(HERE, "broker.py"), str(BP)); time.sleep(2)
    check("U5. broker back: MQTT reconnects", wait(lambda: (status() or {}).get("mqttConnected"), 40))

    # ---- U6. power cut while a swipe is held
    n = len(usb_rows()); typed("1005")
    check("U6. swipe held", wait(lambda: local().get("held") == 1), local())
    stop("gw", signal.SIGKILL); time.sleep(1); start_gateway(hold_ms=1500)
    check("U6. after kill -9 the held swipe is still in the queue file", wait(lambda: (status() or {}).get("queue", {}).get("held", 0) + (status() or {}).get("queue", {}).get("pending", 0) >= 1, 10), status())
    check("U6. it is delivered when its hold has run out, exactly once", wait(lambda: len(usb_rows()) == n + 1, 40) and not any(a["dup"] for a in accepted()), accepted()[-2:])
    keys = [a["key"] for a in usb_rows()]
    check("U6. every USB swipe of this drill has its own unique negative key", len(keys) == len(set(keys)) and all(k < 0 for k in keys), keys)
finally:
    for n in list(procs):
        try: stop(n, signal.SIGKILL)
        except Exception: pass
    shutil.rmtree(tmp, ignore_errors=True)
print("\nALL PASS" if ok_all else "\nFAILURES"); sys.exit(0 if ok_all else 1)
