#!/usr/bin/env python3
"""
Sensor simulator: plays a few sensors that deliver reads to the barn gateway over its HTTP door (POST /), the way a sensor on
the LAN, an Ethernet reader or a serial-to-HTTP bridge would. Use it to see HTTP reads arrive, wait for the reader's own copy,
be sent to the cloud, and show up in the console, without any hardware.

    python sensor_simulator.py                       # one read every ~2 s from the six default sensors, until Ctrl-C
    python sensor_simulator.py --count 20 --interval 0.5
    python sensor_simulator.py --sensors 03F0,C1C4 --tag 042FAC02AD1490     # only these sensors, only this tag
    python sensor_simulator.py --gateway http://192.168.1.50:8090 --token <EDGE_INGEST_TOKEN>

Every read is a Safetrust-shaped event:  {"TIMESTAMP": <ms>, "CARD_UUID": "<14 hex>", "READER_NAME": "<sensor>"}.
No EVENT_KEY is sent: the gateway derives one from the content, so a simulated read can never collide with a real one.
The tags are random 14-digit hex UIDs that start with 04 (like 042FAC02AD1490); a pool of --tags of them is reused so the
same animal is seen by different sensors over time. The credential map (LOCAL_CREDENTIAL_MAP) is for typed USB numbers only:
it plays no part here, because these sensors send the whole tag.

These reads are FAKE. The gateway forwards them to the cloud it is configured for (CLOUD_BASE_URL), where they become real
rows. Point the gateway at a test backend, or accept that the demo cloud gets them (the simulator pauses 3 s to let you stop).
Standard library only.
"""
import argparse
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

SENSORS = ("03F0", "C1C4", "0ED4", "03EC", "56CA", "F9E2")
HEX = "0123456789ABCDEF"


def random_tag(rng: random.Random) -> str:
    """14 hex digits starting with 04, the shape of 042FAC02AD1490 / 04143802F32290 / 044E6602AD1490 / 04B86592C21290."""
    return "04" + "".join(rng.choice(HEX) for _ in range(12))


def make_event(sensor: str, tag: str, now_ms: int = None) -> dict:
    return {"TIMESTAMP": int(time.time() * 1000) if now_ms is None else now_ms, "CARD_UUID": tag, "READER_NAME": sensor}


def post(gateway: str, event: dict, token: str = "", timeout: float = 5.0):
    request = urllib.request.Request(gateway.rstrip("/") + "/", data=json.dumps(event).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **({"X-Edge-Token": token} if token else {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, json.loads(response.read() or b"{}")


def gateway_status(gateway: str):
    try:
        with urllib.request.urlopen(gateway.rstrip("/") + "/status", timeout=5) as response:
            return json.loads(response.read())
    except (urllib.error.URLError, OSError, ValueError):
        return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Simulated HTTP sensors for the barn edge gateway.")
    ap.add_argument("--gateway", default=os.getenv("EDGE_URL", "http://127.0.0.1:8090"), help="the gateway's address (default http://127.0.0.1:8090)")
    ap.add_argument("--token", default=os.getenv("EDGE_INGEST_TOKEN", ""), help="EDGE_INGEST_TOKEN, if the gateway has one")
    ap.add_argument("--sensors", default=",".join(SENSORS), help="comma separated sensor (reader) names; default: " + ",".join(SENSORS))
    ap.add_argument("--tag", action="append", default=[], help="send this tag (repeatable) instead of random ones")
    ap.add_argument("--tags", type=int, default=8, help="how many random tags to cycle through (0 = a new random tag every read); default 8")
    ap.add_argument("--interval", type=float, default=2.0, help="average seconds between reads (random, 0.5x to 1.5x); default 2")
    ap.add_argument("--count", type=int, default=0, help="stop after this many reads (0 = until Ctrl-C)")
    ap.add_argument("--seed", type=int, default=None, help="repeat the same sequence of sensors and tags")
    ap.add_argument("--yes", action="store_true", help="do not pause before sending to a gateway that forwards to a cloud")
    args = ap.parse_args(argv)

    sensors = [s.strip() for s in args.sensors.split(",") if s.strip()]
    if not sensors:
        ap.error("--sensors is empty")
    rng = random.Random(args.seed)
    tags = [t.strip().upper() for t in args.tag if t.strip()] or [random_tag(rng) for _ in range(max(0, args.tags))]

    status = gateway_status(args.gateway)
    if status is None:
        print(f"Cannot reach the gateway at {args.gateway}. Start it first (python barn_edge_gateway.py).", file=sys.stderr)
        return 2
    print(f"Gateway {args.gateway}: link to the cloud {status.get('wan')}, reader feed {'connected' if status.get('mqttConnected') else 'not connected'}.")
    print(f"Sensors: {', '.join(sensors)}. Tags: {', '.join(tags) if tags else 'a new random one each read'}.")
    if not args.yes:
        print("These reads are fake and will be forwarded to the cloud this gateway is set up for. Ctrl-C within 3 s to stop.")
        try:
            time.sleep(3)
        except KeyboardInterrupt:
            return 130

    sent = 0
    try:
        while not args.count or sent < args.count:
            sensor, tag = rng.choice(sensors), (rng.choice(tags) if tags else random_tag(rng))
            event = make_event(sensor, tag)
            try:
                code, body = post(args.gateway, event, args.token)
                result = (f"matched with the reader's own event (not sent)" if body.get("matched") else
                          "repeat, ignored" if body.get("duplicates") else "taken")
                print(f"{time.strftime('%H:%M:%S')}  {sensor}  {tag}  HTTP {code}: {result}")
            except urllib.error.HTTPError as ex:
                print(f"{time.strftime('%H:%M:%S')}  {sensor}  {tag}  refused: HTTP {ex.code} {ex.read()[:120].decode('utf-8', 'replace')}")
            except (urllib.error.URLError, OSError) as ex:
                print(f"{time.strftime('%H:%M:%S')}  {sensor}  {tag}  no answer from the gateway: {ex}")
            sent += 1
            if not args.count or sent < args.count:
                time.sleep(args.interval * rng.uniform(0.5, 1.5))
    except KeyboardInterrupt:
        pass
    print(f"Done: {sent} simulated read{'s' if sent != 1 else ''}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
