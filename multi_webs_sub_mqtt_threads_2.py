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
import sqlite3
import base64
import json


mqtt.Client.connected_flag = False
clients=[]
receive_messages_output_file = 'receive_message_output_file.csv'
g_delay_seconds = 0
g_enable_event_log = False
g_broker_host = ''
g_broker_port = 0
g_max_workers = 0

# SQLite setup
file_path = "./"
db_path = file_path + "events.db"
conn = sqlite3.connect(db_path, check_same_thread=False)
cursor = conn.cursor()
cursor.execute("""
    CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        topic TEXT,
        timestamp TEXT,
        payload_json TEXT
    )
""")
conn.commit()

# Last message tracking
last_message_time = datetime.now(timezone.utc)

def gen_topic(device_key, company_key):
    topic_name = "Web/{0}/Monitoring/{1}".format(company_key, device_key)
    return topic_name;

def gen_client_id(device_key):
    return "Web_{0}".format(device_key)

def gen_username(device_key):
    return device_key

def gen_pw(token):
    return "token/{0}".format(token)

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print("Connected to MQTT Broker!")
        client.connected_flag = True
        subscribe(client, userdata)
    else:
        print("Bad connection Returned code=",rc)
        client.connected_flag = True
        log_message('Failed Connection', 'device_key=' + userdata['device_key'] + '-- returnCode=' + str(rc))
        log_message_intodb('Failed Connection', 'device_key=' + userdata['device_key'] + '-- returnCode=' + str(rc))
        client.loop_stop()

def on_disconnect(client, userdata, rc):
    if rc == 0:
        print("Disconnect from client.")
        client.loop_stop()
    else:
        print("Unexpected disconnection, return code: ", rc)
        client.loop_stop()

def on_log(client, userdata, level, buf):
    print("log: ",buf)

def on_subscribe(client, userdata, mid, granted_qos):
    topic = gen_topic(userdata['device_key'], userdata['company_key'])
    print("Subscribe successfully to topic: ", topic)
    log_message('Successful Subscribe', 'topic=' + topic)
    log_message_intodb(topic, 'Successful Subscribe')


def on_message(client, userdata, msg):
    decoded = base64.b64decode(msg.payload).decode('utf-8')
    data = json.loads(decoded)
    print(f"Received `{data}` from `{msg.topic}` topic")
    log_message(msg.topic, str(json.dumps(data)))
    log_message_intodb(msg.topic, json.dumps(data))


def log_message(topic, message):
    with open(receive_messages_output_file, mode='a+') as output_file:
        fWriter = csv.writer(output_file, delimiter=',', quotechar='"', quoting=csv.QUOTE_MINIMAL)
        dtime = datetime.now(timezone.utc).strftime("%m/%d/%Y, %H:%M:%S")
        fWriter.writerow([dtime, topic, message])

def log_message_intodb(topic, message):
    dtime = datetime.now(timezone.utc).strftime("%m/%d/%Y, %H:%M:%S")
    cursor.execute("INSERT INTO events (topic, timestamp, payload_json) VALUES (?, ?, ?)", (topic, dtime, message))
    conn.commit()

def connect_mqtt(sensor_token, device_key, company_key) -> mqtt:
    client_id = gen_client_id(device_key)
    username = gen_username(device_key)
    password = gen_pw(sensor_token)
    userdata = {
        "device_key": device_key,
        "company_key": company_key,
        "password": password
    }

    #client = mqtt.Client(client_id, mqtt.MQTTv311, "websockets", mqtt.CallbackAPIVersion.VERSION1)
    try:
        client = mqtt.Client(client_id, transport="websockets")  # pre-v2 style
    except TypeError:
        client = mqtt.Client(client_id=client_id, transport="websockets", 
                       callback_api_version=mqtt.CallbackAPIVersion.VERSION1)
    client.ws_set_options(path="/mqtt", headers=None)
    client.tls_set(ca_certs="ca_cert.pem", tls_version=ssl.PROTOCOL_TLSv1_2)
    client.tls_insecure_set(True)
    client.username_pw_set(username, password)
    client.user_data_set(userdata)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_log=on_log

    client.connect(g_broker_host, g_broker_port, keepalive=30)
    return client

def subscribe(client, userdata):
    device_key = userdata['device_key']
    company_key = userdata['company_key']
    pwd = userdata['password']

    # qos 2
    topic = gen_topic(device_key, company_key)
    topic_subc = [(pwd,2), (topic,2)]
    result = client.subscribe(topic_subc)
    print(result)
    status = result[0]
    if status == 0:
        print(f"Successful to subscribe into topic `{topic}`")
    else:
        print(f"Failed to subscribe into topic `{topic}`")

    client.on_subscribe = on_subscribe
    client.on_message = on_message

def read_data_from_csv(file_name):
    rows=[]
    try:
        f = open(file_name)
        csv_reader = csv.reader(f)
        for row in csv_reader:
            rows.append(row)
    finally:
        f.close()

    return rows


def create_connection_threads(data_csv):
    try:
        with PoolExecutor(max_workers=g_max_workers) as executor:
            for _ in executor.map(create_connection, data_csv):
                pass
    except Exception as e:
        logging.error(traceback.format_exc())

def create_connection(row):
    print(row)
    try:
        if len(row) == 3:
            device_key = row[0]
            device_key = device_key.strip()
            token = row[1]
            token = token.strip()
            company_key = row[2]
            company_key = company_key.strip()

            print(f"Process for sensor device_key `{device_key}`")
            client = connect_mqtt(token, device_key, company_key)
                
            clients.append(client)
            client.loop_start()

            timeout = time.time() + 60*1.5   # 1.5 minutes from now
            while not client.connected_flag:
                if time.time() > timeout:
                    log_message('Failed Connection', 'device_key=' + device_key + '-- timeout')
                    log_message_intodb('Failed Connection', 'device_key=' + device_key + '-- timeout')
                    break
                time.sleep(0.05)

            if g_delay_seconds > 0:
                time.sleep(g_delay_seconds)
    except Exception as e:
        logging.error(traceback.format_exc())

def exit_handler():
    print('Waiting to all clients disconnect...')
    for client in clients:
        client.disconnect()
        client.loop_stop()
        time.sleep(0.3)
    print('Script is ending!')

# atexit.register(exit_handler)


def main(argv):
    broker_host = ''
    broker_port = ''
    input_file = ''
    output_file = ''
    delay_seconds = '0'
    enable_event_log = False
    max_workers = '10'
    try:
        opts, args = getopt.getopt(argv,"u:p:f:o:d:e:w:",["broker_host=", "broker_port=","input_file=","output_file=", "delay_seconds=", "enable_event_log=", "max_workers="])
    except getopt.GetoptError:
        print('argument error')
        print('multi_webs_sub_mqtt_threads.py -u <broker_host> -p <broker_port> -f <input_file> -o <output_file> -d <delay_seconds> -e <enable_event_log> -w <max_workers>')
        sys.exit(2)
    for opt, arg in opts:
        if opt == '-h':
            print('multi_webs_sub_mqtt_threads.py -u <broker_host> -p <broker_port> -f <input_file> -o <output_file> -d <delay_seconds> -e <enable_event_log> -w <max_workers>')
            sys.exit()
        elif opt in ("-u", "--broker_host"):
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
            enable_event_log = arg
        elif opt in ("-w", "--max_workers"):
            max_workers = arg
    print('-- Broker Host : ', broker_host)
    print('-- Broker Port : ', broker_port)
    print('-- Input File : ', input_file)
    print('-- Output File : ', output_file)
    print('-- Delay Seconds : ', delay_seconds)
    print('-- Enable Event Log : ', enable_event_log)
    print('-- Max Workers : ', max_workers)

    no_threads = threading.active_count()
    print("current threads =", no_threads)

    # Sensor Connect And Subscribe MQTT
    global receive_messages_output_file
    receive_messages_output_file = output_file
    global g_delay_seconds
    g_delay_seconds = int(delay_seconds)
    global g_enable_event_log
    g_enable_event_log = enable_event_log

    global g_broker_host
    g_broker_host = broker_host
    global g_broker_port
    g_broker_port = int(broker_port)
    global g_max_workers
    g_max_workers = int(max_workers)

    data_csv = read_data_from_csv(input_file)
    create_connection_threads(data_csv)

    
        

    
    try:
        while True:
            no_threads = threading.active_count()
            print("current threads =", no_threads)
            time.sleep(10)
    except KeyboardInterrupt:
        print("Interrupted by keyboard.")
    finally:
        exit_handler()


if __name__ == "__main__":
    main(sys.argv[1:])