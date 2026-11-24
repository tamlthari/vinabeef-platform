"""Plays a wall reader: publishes ONE reader event to an MQTT broker, the way Safetrust publishes a tap. For the test bench only.

  python drill/publish_reader_event.py --credential 36 --card 042FAC02AD1490 --reader DrC0F8
  options: --url mqtt://127.0.0.1:1883   --topic Web/475/Monitoring/728589   --key <event number>   --no-credential

Type the same number on the gateway's USB input (stdin) first: if this event follows within LOCAL_HOLD_MS (6 s), the gateway
treats the two as one tap and sends only this one. Do not point it at the real Safetrust broker."""
import argparse
import json
import sys
import time
from urllib.parse import urlparse

import paho.mqtt.client as mqtt

ap = argparse.ArgumentParser()
ap.add_argument("--url", default="mqtt://127.0.0.1:1883")
ap.add_argument("--topic", default="Web/475/Monitoring/728589")
ap.add_argument("--reader", default="DrC0F8")
ap.add_argument("--card", default="042FAC02AD1490")
ap.add_argument("--credential", default="36")
ap.add_argument("--no-credential", action="store_true", help="leave CREDENTIAL_IDENTIFIER out")
ap.add_argument("--key", type=int, default=int(time.time()) % 10_000_000 + 9_000_000)
args = ap.parse_args()

u = urlparse(args.url)
if u.scheme not in ("mqtt", "tcp"):
    sys.exit("This tool only talks to a plain mqtt:// broker (the test bench broker).")
try:                                                    # paho-mqtt 2.x
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"bench_reader_{args.key}")
except AttributeError:                                  # paho-mqtt 1.x
    client = mqtt.Client(client_id=f"bench_reader_{args.key}")
client.connect(u.hostname, u.port or 1883)
client.loop_start()
event = {"EVENT_KEY": args.key, "TIMESTAMP": int(time.time() * 1000), "CARD_UUID": args.card, "READER_NAME": args.reader,
         "COMPANY_KEY": 475, "NOTES": {"ACTION": "OPEN_DOOR"}}
if not args.no_credential:
    event["CREDENTIAL_IDENTIFIER"] = args.credential
client.publish(args.topic, json.dumps(event), qos=1).wait_for_publish(5)
client.loop_stop()
client.disconnect()
print(f"published event {args.key}: card {args.card}, reader {args.reader}, credential {'-' if args.no_credential else args.credential}")
