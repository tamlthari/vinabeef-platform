import json
import time
import uuid
import paho.mqtt.client as mqtt

BROKER = "localhost"
PORT = 1883
FARM_ID = "FARM-DAK-LAK"
BARN_ID = "BARN-01"
GATE_ID = "GATE-01"

TOPIC = f"vinabeef/farms/{FARM_ID}/barns/{BARN_ID}/gates/{GATE_ID}/rfid/reads"

client = mqtt.Client(client_id="gate-simulator-01")
client.connect(BROKER, PORT, 60)

print(f"Simulator connected to {BROKER}:{PORT}. Publishing test movement...")

def simulate_passage(tag_epc: str, direction: str):
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
    payload = {
        "messageId": str(uuid.uuid4()),
        "readerId": f"RDR-{GATE_ID}",
        "farmId": FARM_ID,
        "barnId": BARN_ID,
        "gateId": GATE_ID,
        "tagEpc": tag_epc,
        "direction": direction,
        "readTimestamp": timestamp,
        "rssi": -62
    }
    client.publish(TOPIC, json.dumps(payload), qos=1)
    print(f"Sent: {tag_epc} [{direction}] at {timestamp}")

# Simulate passage and duplicate read
simulate_passage("E28011606000020498760001", "OUT")
time.sleep(0.5)
# Duplicate read within window (should be deduplicated by backend)
simulate_passage("E28011606000020498760001", "OUT") 

client.disconnect()