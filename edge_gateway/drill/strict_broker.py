"""
A tiny MQTT 3.1.1 broker (plain TCP) that behaves like the Safetrust broker did in the field: it accepts the login and
then HANGS UP on a subscription it does not like, without answering it. Used to test the gateway and drill/mqtt_probe.py.

Rules (each one optional). A subscription is dropped when:
  need_token_topic   it does not contain a topic that starts with "token/"
  min_qos            it asks for less than this QoS on any topic
  key_in_client_id   the client id does not contain this text (the device key)
Anything it accepts is answered with a SUBACK that grants what was asked, and every message published to it through
`publish()` is delivered to the clients that subscribed to a matching topic.

    python drill/strict_broker.py 1884 --need-token --min-qos 2 --key-in-client-id 728589
"""
import argparse
import socket
import socketserver
import threading


def _read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    return data


def _read_packet(sock):
    first = _read_exact(sock, 1)[0]
    mult, length = 1, 0
    while True:
        b = _read_exact(sock, 1)[0]
        length += (b & 0x7F) * mult
        if not b & 0x80:
            break
        mult *= 128
    return first, _read_exact(sock, length) if length else b""


def _varint(n):
    out = b""
    while True:
        b, n = n % 128, n // 128
        out += bytes([b | 0x80 if n else b])
        if not n:
            return out


def _str(data, i):
    n = int.from_bytes(data[i:i + 2], "big")
    return data[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n


class StrictBroker:
    def __init__(self, port=0, need_token_topic=False, min_qos=0, key_in_client_id=""):
        self.need_token_topic, self.min_qos, self.key_in_client_id = need_token_topic, min_qos, key_in_client_id
        self.log, self.sessions, self.lock = [], [], threading.Lock()
        broker = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                broker._serve(self.request)

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self.server = Server(("127.0.0.1", port), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def note(self, *what):
        with self.lock:
            self.log.append(what)

    def _serve(self, sock):
        client_id, subs = "", []
        try:
            while True:
                first, body = _read_packet(sock)
                kind = first >> 4
                if kind == 1:                                            # CONNECT
                    _, i = _str(body, 0)                                 # protocol name
                    i += 4                                               # level, flags, keep-alive
                    client_id, _ = _str(body, i)
                    self.note("connect", client_id)
                    sock.sendall(b"\x20\x02\x00\x00")
                elif kind == 8:                                          # SUBSCRIBE
                    pid, i, wanted = body[:2], 2, []
                    while i < len(body):
                        topic, i = _str(body, i)
                        wanted.append((topic, body[i] & 3))
                        i += 1
                    why = self._reject(client_id, wanted)
                    if why:
                        self.note("hangup", client_id, why)
                        return                                           # close without a SUBACK
                    subs = wanted
                    with self.lock:
                        self.sessions.append((sock, subs))
                    self.note("subscribed", client_id, [q for _, q in wanted])
                    sock.sendall(b"\x90" + _varint(2 + len(wanted)) + pid + bytes(q for _, q in wanted))
                elif kind == 12:                                         # PINGREQ
                    sock.sendall(b"\xd0\x00")
                elif kind == 14:                                         # DISCONNECT
                    return
        except (ConnectionError, OSError):
            pass
        finally:
            with self.lock:
                self.sessions = [(s, t) for s, t in self.sessions if s is not sock]
            try:
                sock.close()
            except OSError:
                pass

    def _reject(self, client_id, wanted):
        if self.need_token_topic and not any(t.startswith("token/") for t, _ in wanted):
            return "no token topic"
        if any(q < self.min_qos for _, q in wanted):
            return f"QoS below {self.min_qos}"
        if self.key_in_client_id and self.key_in_client_id not in client_id:
            return "client id has no device key"
        return ""

    def publish(self, topic, payload: bytes):
        """Deliver a QoS 0 message to every subscriber of `topic`. Returns how many received it."""
        body = len(topic).to_bytes(2, "big") + topic.encode() + payload
        packet = b"\x30" + _varint(len(body)) + body
        n = 0
        with self.lock:
            targets = [s for s, subs in self.sessions if any(t == topic for t, _ in subs)]
        for s in targets:
            try:
                s.sendall(packet)
                n += 1
            except OSError:
                pass
        return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", type=int)
    ap.add_argument("--need-token", action="store_true")
    ap.add_argument("--min-qos", type=int, default=0)
    ap.add_argument("--key-in-client-id", default="")
    a = ap.parse_args()
    b = StrictBroker(a.port, a.need_token, a.min_qos, a.key_in_client_id)
    print(f"strict broker on 127.0.0.1:{b.port}", flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
