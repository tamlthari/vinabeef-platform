"""
Which MQTT settings does this broker accept?  Run it on the gateway computer when the console says the broker
"hangs up right after the login", or when the log shows "MQTT disconnected (rc=7)" a quarter of a second after
"MQTT connected" over and over.

It reads the SAME settings as the gateway (MQTT_URL, MQTT_USERNAME, MQTT_PASSWORD, MQTT_TOPICS, MQTT_TLS_INSECURE,
MQTT_CA_CERTS), connects a few times with a different client id and a different subscription each time, and prints
for each whether the broker kept the connection and answered the subscription. It only subscribes; it never publishes.

    set -a; . ./.env; set +a
    python drill/mqtt_probe.py                 # all eight variants, about a minute
    python drill/mqtt_probe.py --only 1,3      # just those variants
    python drill/mqtt_probe.py --hold 10       # wait 10 s per variant instead of 6

Stop the gateway first: a broker can limit how many connections one account may have, and this should not compete.
The probe uses client ids of its own (Web_Probe...), so it never knocks another program off the broker. The token
is never printed.
"""
import argparse
import os
import random
import string
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from barn_edge_gateway import Config, build_mqtt_client  # noqa: E402

CONNECT_TIMEOUT_S = 15.0


def variants(cfg: Config):
    """(label, client id, subscription list) in the order of how closely they copy the cloud's own clients."""
    key = cfg.mqtt_username
    tag = "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(4))
    topics = [t.strip() for t in cfg.mqtt_topics.split(",") if t.strip()]
    token = cfg.mqtt_password if cfg.mqtt_password.startswith("token/") else ""
    with_key = f"Web_Probe_{key}_{tag}" if key else None
    without = f"Web_Probe{tag}"
    subs = [
        ("token topic + topics, QoS 2", ([(token, 2)] if token else []) + [(t, 2) for t in topics]),
        ("topics only, QoS 2", [(t, 2) for t in topics]),
        ("topics only, QoS 1", [(t, 1) for t in topics]),
        ("login only, no subscription", []),
    ]
    out = []
    for client_id, kind in ((with_key, "id ends with the device key"), (without, "id without the device key")):
        if client_id is None:
            continue
        for label, sub in subs:
            if label.startswith("token") and not token:
                continue                                          # nothing to try: the password is not a token/... one
            out.append((f"{kind}; {label}", client_id, sub, kind.startswith("id ends"), label))
    return out


def run_one(cfg: Config, client_id: str, subscription: list, hold_s: float) -> dict:
    client, host, port = build_mqtt_client(cfg, client_id)
    r = {"connack": None, "up_at": None, "suback": None, "down_at": None, "down_rc": None, "closing": False}
    done = threading.Event()

    def on_connect(c, userdata, flags, rc):
        r["connack"] = rc
        if rc == 0:
            r["up_at"] = time.monotonic()
            if subscription:
                c.subscribe(list(subscription))
        else:
            done.set()

    def on_subscribe(c, userdata, mid, granted):
        r["suback"] = list(granted)

    def on_disconnect(c, userdata, rc):
        if r["down_at"] is None and not r["closing"]:           # our own hang-up at the end is not the broker's
            r["down_at"], r["down_rc"] = time.monotonic(), rc
        done.set()

    client.on_connect, client.on_subscribe, client.on_disconnect = on_connect, on_subscribe, on_disconnect
    started = time.monotonic()
    try:
        client.connect_async(host, port, keepalive=30)
        client.loop_start()
        while r["up_at"] is None and not done.is_set() and time.monotonic() - started < CONNECT_TIMEOUT_S:
            time.sleep(0.05)
        if r["up_at"] is not None:
            done.wait(hold_s)                                     # connected: does it stay that way?
    finally:
        r["closing"] = True
        try:
            client.disconnect()
        except Exception:
            pass
        client.loop_stop()
    r["held_s"] = (r["down_at"] or time.monotonic()) - r["up_at"] if r["up_at"] else 0.0
    r["stayed"] = r["up_at"] is not None and r["down_at"] is None
    return r


def verdict(r: dict, subscription: list) -> tuple:
    if r["connack"] is None:
        return False, "no answer from the broker"
    if r["connack"] != 0:
        return False, f"login refused (code {r['connack']})"
    if r["stayed"] and (r["suback"] is not None or not subscription):
        if subscription:
            bad = [g for g in r["suback"] if g >= 128]
            return (not bad), (f"stayed up, subscription answered {r['suback']}" + (", some topics REFUSED" if bad else ""))
        return True, f"stayed up {r['held_s']:.1f} s with no subscription"
    if r["stayed"]:
        return False, "stayed up but never answered the subscription"
    after = "after the subscription answered" if r["suback"] is not None else "before it answered the subscription"
    return False, f"hung up {r['held_s'] * 1000:.0f} ms after the login, {after} (code {r['down_rc']})"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Try which MQTT client id / subscription the broker accepts.")
    ap.add_argument("--hold", type=float, default=6.0, help="seconds to wait per variant (default 6)")
    ap.add_argument("--only", default="", help="comma separated variant numbers to run, e.g. 1,3")
    args = ap.parse_args(argv)

    cfg = Config.from_env()
    if not cfg.mqtt_url:
        print("MQTT_URL is not set. Load your settings first:  set -a; . ./.env; set +a")
        return 2
    if not cfg.mqtt_topics.strip():
        print("MQTT_TOPICS is empty: there is nothing to subscribe to. Set it, for example Web/475/Monitoring/728589.")
        return 2
    plan = variants(cfg)
    chosen = {int(n) for n in args.only.split(",") if n.strip().isdigit()}
    print(f"Broker {cfg.mqtt_url.split('@')[-1]} as user {cfg.mqtt_username or '(none)'}; "
          f"{len(plan)} variants, up to {args.hold:g} s each.\n")
    working = []
    for n, (name, client_id, subscription, has_key, label) in enumerate(plan, 1):
        if chosen and n not in chosen:
            continue
        r = run_one(cfg, client_id, subscription, args.hold)
        ok, text = verdict(r, subscription)
        print(f" {n}. {'OK  ' if ok else 'FAIL'} {name}\n        {text}", flush=True)
        if ok and subscription:
            working.append((n, has_key, label))
        time.sleep(1.0)
    print()
    if not working:
        print("No variant that subscribes stayed connected. The login works but the broker drops the subscription whatever is asked.\n"
              "Send this table to Safetrust support with the client id and topic you use, and ask what the broker requires.")
        return 1
    n, has_key, label = working[0]
    print(f"Use variant {n}:")
    print(f"    MQTT_CLIENT_ID={'Web_Edge_' + cfg.mqtt_username + '_<a name unique to this gateway>' if has_key else 'Web_Edge_<a name unique to this gateway>'}")
    print(f"    MQTT_QOS={'1' if 'QoS 1' in label else '2'}")
    print(f"    MQTT_TOKEN_TOPIC={'true' if label.startswith('token') else 'false'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
