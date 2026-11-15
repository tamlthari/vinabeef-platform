import paho.mqtt.client as mqtt
import sys
import getopt
import ssl
import time
import csv
import threading
from datetime import datetime, timezone
import traceback
import logging
from concurrent.futures import ThreadPoolExecutor as PoolExecutor
import base64
import json
import urllib.request
import urllib.error

SPRING_BASE_URL = "http://localhost:8080/api/v1/ingestion"
FAILOVER_CHECK_INTERVAL_SEC = 1.0

# Failover Watchdog State
g_failover_active = False
g_failover_reason = "Initializing Watchdog"
g_watchdog_running = True

mqtt.Client.connected_flag = False
clients = []
receive_messages_output_file = 'receive_message_output_file.csv'
g_delay_seconds = 0
g_enable_event_log = False
g_broker_host = ''
g_broker_port = 0
g_max_workers = 0

def watchdog_poll_spring_health():
    """Continuously checks if Direct Spring Boot MQTT is degraded or disconnected."""
    global g_failover_active, g_failover_reason
    url = f"{SPRING_BASE_URL}/failover-status"

    while g_watchdog_running:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "VinaBeef-Failover-Agent"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode('utf-8'))
                    direct_connected = data.get("directMqttConnected", False)
                    latency_breached = data.get("latencyBreached", False)
                    recent_latency = data.get("recentAvgLatencyMs", 0.0)

                    if not direct_connected:
                        g_failover_active = True
                        g_failover_reason = f"Spring Direct MQTT DISCONNECTED ({data.get('disconnectReason')})"
                    elif latency_breached:
                        g_failover_active = True
                        g_failover_reason = f"Spring Direct MQTT LATENCY BREACHED: {recent_latency}ms > 500ms"
                    else:
                        if g_failover_active:
                            print(f"\n[WATCHDOG] Primary Direct MQTT recovered (Latency: {recent_latency}ms). Standing down to HOT_STANDBY.")
                        g_failover_active = False
                        g_failover_reason = "Direct MQTT Healthy"
        except urllib.error.URLError:
            g_failover_active = True
            g_failover_reason = "Spring Boot Backend Unreachable on Port 8080"
        except Exception as e:
            g_failover_active = True
            g_failover_reason = f"Watchdog error: {str(e)}"

        time.sleep(FAILOVER_CHECK_INTERVAL_SEC)

def gen_topic(device_key, company_key):
    return f"Web/{company_key}/Monitoring/{device_key}"

def gen_client_id(device_key):
    return f"Web_Standby_{device_key}"

def gen_username(device_key):
    return device_key

def gen_pw(token):
    return f"token/{token}"

def forward_to_spring_boot(payload_dict):
    try:
        data_bytes = json.dumps(payload_dict).encode('utf-8')
        req = urllib.request.Request(
            f"{SPRING_BASE_URL}/safetrust-event",
            data=data_bytes,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            if resp.status == 200:
                print(f" -> [FAILOVER FORWARD] Event {payload_dict.get('EVENT_KEY')} transmitted to Spring Boot.")
    except Exception as e:
        print(f" [!] Failover forward failed: {e}")

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print("Connected to Safetrust MQTT Broker in HOT-STANDBY mode.")
        client.connected_flag = True
        subscribe(client, userdata)
    else:
        print("Connection failed with code: ", rc)
        client.connected_flag = True
        client.loop_stop()

def on_disconnect(client, userdata, rc):
    print("Safetrust Standby Client Disconnected. Code:", rc)
    client.loop_stop()

def on_subscribe(client, userdata, mid, granted_qos):
    topic = gen_topic(userdata['device_key'], userdata['company_key'])
    print(f"Subscribed to topic: {topic}")

def on_message(client, userdata, msg):
    try:
        decoded = base64.b64decode(msg.payload).decode('utf-8')
        data = json.loads(decoded)

        event_key = data.get('EVENT_KEY')
        notes = data.get('NOTES', {})
        user_name = notes.get('USER_NAME', 'UNKNOWN')
        card_uuid = data.get('CARD_UUID', '')
        reader_name = data.get('READER_NAME', notes.get('READER_NAME', 'UNKNOWN'))

        if g_failover_active:
            print(f"\n[FAILOVER ACTIVE] {g_failover_reason}")
            print(f" -> Relaying Read: Tag={card_uuid}, Animal={user_name}, Gate={reader_name}, EventKey={event_key}")
            data['rawPayload'] = decoded
            forward_to_spring_boot(data)
        else:
            # Hot-standby mode: Direct Spring Boot consumer is handling this event in-process
            print(f"[HOT_STANDBY] Event {event_key} observed. (Primary Direct MQTT handling event. Forwarding suppressed.)")

        if g_enable_event_log:
            with open(receive_messages_output_file, mode='a+') as f:
                writer = csv.writer(f, delimiter=',', quotechar='"', quoting=csv.QUOTE_MINIMAL)
                dtime = datetime.now(timezone.utc).strftime("%m/%d/%Y, %H:%M:%S")
                writer.writerow([dtime, msg.topic, event_key, user_name, card_uuid, reader_name, g_failover_active])

    except Exception as e:
        print(f"Error handling MQTT frame: {e}")

def connect_mqtt(sensor_token, device_key, company_key) -> mqtt.Client:
    client_id = gen_client_id(device_key)
    username = gen_username(device_key)
    password = gen_pw(sensor_token)
    userdata = {
        "device_key": device_key,
        "company_key": company_key,
        "password": password
    }

    try:
        client = mqtt.Client(client_id, transport="websockets")
    except TypeError:
        client = mqtt.Client(client_id=client_id, transport="websockets",
                             callback_api_version=mqtt.CallbackAPIVersion.VERSION1)

    client.ws_set_options(path="/mqtt", headers=None)
    client.tls_set(cert_reqs=ssl.CERT_NONE, tls_version=ssl.PROTOCOL_TLSv1_2)
    client.tls_insecure_set(True)
    client.username_pw_set(username, password)
    client.user_data_set(userdata)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_subscribe = on_subscribe
    client.on_message = on_message

    client.connect(g_broker_host, g_broker_port, keepalive=30)
    return client

def subscribe(client, userdata):
    device_key = userdata['device_key']
    company_key = userdata['company_key']
    pwd = userdata['password']

    topic = gen_topic(device_key, company_key)
    topic_subc = [(pwd, 2), (topic, 2)]
    result = client.subscribe(topic_subc)
    if result[0] != 0:
        print(f"Subscription failed for topic: {topic}")

def read_data_from_csv(file_name):
    rows = []
    with open(file_name, mode='r') as f:
        csv_reader = csv.reader(f)
        for row in csv_reader:
            if row:
                rows.append(row)
    return rows

def create_connection_threads(data_csv):
    try:
        with PoolExecutor(max_workers=g_max_workers) as executor:
            for _ in executor.map(create_connection, data_csv):
                pass
    except Exception:
        logging.error(traceback.format_exc())

def create_connection(row):
    try:
        if len(row) >= 3:
            device_key = row[0].strip()
            token = row[1].strip()
            company_key = row[2].strip()

            client = connect_mqtt(token, device_key, company_key)
            clients.append(client)
            client.loop_start()

            timeout = time.time() + 90
            while not client.connected_flag:
                if time.time() > timeout:
                    print(f"Connection timeout for {device_key}")
                    break
                time.sleep(0.05)

            if g_delay_seconds > 0:
                time.sleep(g_delay_seconds)
    except Exception:
        logging.error(traceback.format_exc())

def exit_handler():
    global g_watchdog_running
    g_watchdog_running = False
    for client in clients:
        client.disconnect()
        client.loop_stop()
        time.sleep(0.2)
    print("Safetrust Standby Agent cleanly stopped.")

def main(argv):
    global receive_messages_output_file, g_delay_seconds, g_enable_event_log
    global g_broker_host, g_broker_port, g_max_workers

    broker_host = ''
    broker_port = ''
    input_file = ''
    output_file = ''
    delay_seconds = '0'
    enable_event_log = False
    max_workers = '10'

    try:
        opts, _ = getopt.getopt(argv, "u:p:f:o:d:e:w:", [
            "broker_host=", "broker_port=", "input_file=", "output_file=",
            "delay_seconds=", "enable_event_log=", "max_workers="
        ])
    except getopt.GetoptError:
        sys.exit(2)

    for opt, arg in opts:
        if opt in ("-u", "--broker_host"):
            broker_host = arg
        elif opt in ("-p", "--broker_port"):
            broker_port = arg
        elif opt in ("-f", "--input_file"):
            input_file = arg
        elif opt in ("-o", "--output_file"):
            output_file = arg
        elif opt in ("-d", "--delay_seconds"):
            delay_seconds = arg
        elif opt in ("-e", "--enable_event_log"):
            enable_event_log = (arg.lower() == 'true')
        elif opt in ("-w", "--max_workers"):
            max_workers = arg

    g_broker_host = broker_host
    g_broker_port = int(broker_port)
    receive_messages_output_file = output_file
    g_delay_seconds = int(delay_seconds)
    g_enable_event_log = enable_event_log
    g_max_workers = int(max_workers)

    # 1. Start Background Watchdog Thread
    watchdog_thread = threading.Thread(target=watchdog_poll_spring_health, daemon=True)
    watchdog_thread.start()
    print("[INIT] Watchdog thread started. Monitoring Direct Spring Boot MQTT health...")

    # 2. Connect to Safetrust Broker on Hot-Standby
    data_csv = read_data_from_csv(input_file)
    create_connection_threads(data_csv)

    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        print("Interrupted by user.")
    finally:
        exit_handler()

if __name__ == "__main__":
    main(sys.argv[1:])