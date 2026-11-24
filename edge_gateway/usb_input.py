"""
Input side of the barn gateway's local USB reader path (Safetrust USB IoT reader).

The USB reader hands over the credential number as plain text and NOTHING else: no time, no card UUID.
It can act as a keyboard ("38" or "1005", usually followed by Enter), as a serial (CDC) port, or on stdin for a demo.
This module turns those byte/key streams into (text, time) pairs. It has no knowledge of queues or the cloud.

  parse_credential()   "0038" -> "38"; anything that is not 1-12 digits -> None (the caller rejects and logs it)
  KeyboardDecoder      evdev key codes -> text            (pure state machine, no hardware needed)
  TextDecoder          serial / stdin bytes -> text        (pure state machine)
  KeyboardSource       reads /dev/input/eventX, grabs it so the digits do not also land in a console
  SerialSource         reads a CDC serial port (needs the optional pyserial package)
  StdinSource          reads lines from stdin (demo)

A swipe ends at Enter / newline, or after a short silence (some readers send no terminator). The time of a swipe is
the moment its FIRST character arrived, taken from the gateway clock by the caller.
OSDP is not implemented.
"""
import logging
import os
import re
import select
import struct
import sys
import threading
import time
from dataclasses import dataclass

log = logging.getLogger("edge.usb")

CREDENTIAL_RE = re.compile(r"^[0-9]{1,12}$")


def parse_credential(text):
    """The credential number as Safetrust writes it, or None when the text is not a credential number.
    Leading zeros are dropped so that "0038" and "38" are the same number."""
    if text is None:
        return None
    s = str(text).strip()
    return str(int(s)) if CREDENTIAL_RE.match(s) else None


CARD_UUID_RE = re.compile(r"^[0-9A-Fa-f]{8,32}$")


def parse_credential_map(text: str) -> dict:
    """"36=042FAC02AD1490,1005=04B86592C21290" -> {"36": "042FAC02AD1490", "1005": "04B86592C21290"}.
    For a demo or a barn where the cloud does not know the numbers yet: the gateway puts the card on the swipe itself.
    Fails loudly at start-up on a malformed entry, a number written twice, or two numbers for one card, because a wrong
    card on a movement is worse than a refused start."""
    out, seen = {}, {}
    for part in (p.strip() for p in (text or "").split(",")):
        if not part:
            continue
        number, sep, uuid = (s.strip() for s in part.partition("="))
        credential = parse_credential(number)
        if not sep or credential is None or not CARD_UUID_RE.match(uuid):
            raise ValueError(f"LOCAL_CREDENTIAL_MAP entry '{part}' must look like 38=044E6602AD1490 "
                             "(a number of 1 to 12 digits, then the card UUID in hexadecimal)")
        uuid = uuid.upper()
        if credential in out:
            raise ValueError(f"LOCAL_CREDENTIAL_MAP lists the number {credential} twice")
        if uuid in seen:
            raise ValueError(f"LOCAL_CREDENTIAL_MAP gives the card {uuid} to both {seen[uuid]} and {credential}")
        out[credential], seen[uuid] = uuid, credential
    return out


@dataclass(frozen=True)
class RawSwipe:
    text: str
    t_ms: int          # gateway clock when the first character arrived


# --------------------------------------------------------------------------------------------------------
# Collecting the characters of one swipe
# --------------------------------------------------------------------------------------------------------
class _Burst:
    """One swipe = characters that follow each other closely. Ends at a terminator or after idle_ms of silence."""

    def __init__(self, idle_ms: int, max_len: int):
        self.idle_ms, self.max_len = idle_ms, max_len
        self.chars, self.first_ms, self.last_ms = [], 0, 0

    def add(self, ch: str, t_ms: int):
        out = []
        if self.chars and t_ms - self.last_ms >= self.idle_ms:
            out += self._end()                      # a pause: what we had was a complete swipe
        if not self.chars:
            self.first_ms = t_ms
        self.chars.append(ch)
        self.last_ms = t_ms
        if len(self.chars) > self.max_len:          # runaway input: hand it on (it will be rejected as not a number)
            out += self._end()
        return out

    def terminate(self):
        return self._end()

    def tick(self, now_ms: int):
        if self.chars and now_ms - self.last_ms >= self.idle_ms:
            return self._end()
        return []

    def _end(self):
        if not self.chars:
            return []
        swipe = RawSwipe("".join(self.chars), self.first_ms)
        self.chars = []
        return [swipe]


# --------------------------------------------------------------------------------------------------------
# Keyboard (Linux evdev key codes)
# --------------------------------------------------------------------------------------------------------
_TOP_ROW = {2: "1", 3: "2", 4: "3", 5: "4", 6: "5", 7: "6", 8: "7", 9: "8", 10: "9", 11: "0"}
_KEYPAD = {71: "7", 72: "8", 73: "9", 75: "4", 76: "5", 77: "6", 79: "1", 80: "2", 81: "3", 82: "0"}
_LETTERS = {16: "q", 17: "w", 18: "e", 19: "r", 20: "t", 21: "y", 22: "u", 23: "i", 24: "o", 25: "p",
            30: "a", 31: "s", 32: "d", 33: "f", 34: "g", 35: "h", 36: "j", 37: "k", 38: "l",
            44: "z", 45: "x", 46: "c", 47: "v", 48: "b", 49: "n", 50: "m"}
_SHIFT = {42, 54}
_TERMINATORS = {28, 96, 15}                         # Enter, keypad Enter, Tab
_IGNORED = {29, 97, 56, 100, 58, 69, 125, 126}      # Ctrl, Alt, Caps Lock, Num Lock, Meta: print nothing


class KeyboardDecoder:
    """evdev (code, value) events -> swipes. value: 1 = key down, 0 = up, 2 = auto-repeat.
    Only key-downs add a character. A key it does not know becomes '?', so the swipe is REJECTED later instead of
    silently turning into a different, valid-looking number."""

    def __init__(self, idle_ms: int = 200, max_len: int = 24):
        self._burst = _Burst(idle_ms, max_len)
        self._shift = False

    def feed(self, code: int, value: int, t_ms: int):
        if code in _SHIFT:
            self._shift = value != 0
            return []
        if value != 1:
            return []
        if code in _TERMINATORS:
            return self._burst.terminate()
        if code in _IGNORED:
            return []
        if code in _LETTERS:
            ch = _LETTERS[code].upper() if self._shift else _LETTERS[code]
        elif code in _TOP_ROW:
            ch = "?" if self._shift else _TOP_ROW[code]      # shift + digit is a symbol, not a digit
        elif code in _KEYPAD:
            ch = _KEYPAD[code]
        else:
            ch = "?"
        return self._burst.add(ch, t_ms)

    def tick(self, now_ms: int):
        return self._burst.tick(now_ms)


class TextDecoder:
    """Serial / stdin text -> swipes. CR, LF and Tab end a swipe; spaces are ignored; other control bytes are dropped."""

    def __init__(self, idle_ms: int = 200, max_len: int = 24):
        self._burst = _Burst(idle_ms, max_len)

    def feed(self, data, t_ms: int):
        text = data.decode("latin-1") if isinstance(data, (bytes, bytearray)) else data
        out = []
        for ch in text:
            if ch in "\r\n\t":
                out += self._burst.terminate()
            elif ch.isspace() or not ch.isprintable():
                continue
            else:
                out += self._burst.add(ch, t_ms)
        return out

    def tick(self, now_ms: int):
        return self._burst.tick(now_ms)


# --------------------------------------------------------------------------------------------------------
# Sources (one thread each). Each hands (reader, device, text, t_ms) to `sink`.
# --------------------------------------------------------------------------------------------------------
def _now_ms() -> int:
    return int(time.time() * 1000)


class _Source(threading.Thread):
    kind = "source"

    def __init__(self, device: str, reader: str, sink, clock=_now_ms):
        super().__init__(name=f"usb-{device}", daemon=True)
        self.device, self.reader, self.sink, self.clock = device, reader, sink, clock
        self.stop_event = threading.Event()
        self.connected = False

    def emit(self, swipes):
        for s in swipes:
            try:
                self.sink(self.reader, self.device, s.text, s.t_ms)
            except Exception:                       # a bad swipe must never stop the reader thread
                log.exception("USB swipe from %s could not be handled", self.device)

    def close(self):
        self.stop_event.set()


# evdev: struct input_event { struct timeval time; __u16 type; __u16 code; __s32 value; }  (native sizes)
_EVENT = struct.Struct("@llHHi")
_EV_KEY = 1
_EVIOCGRAB = 0x40044590


class KeyboardSource(_Source):
    """Reads key events from /dev/input/eventX (or an already open descriptor, for tests).
    The device is GRABBED exclusively so the digits do not also reach a console or a desktop on the gateway."""
    kind = "kbd"

    def __init__(self, device, reader, sink, path=None, fd=None, grab=True, idle_ms=200, clock=_now_ms, retry_s=5.0):
        super().__init__(device, reader, sink, clock)
        self.path, self._fd, self.grab, self.retry_s = path, fd, grab, retry_s
        self.decoder = KeyboardDecoder(idle_ms)

    def _open(self):
        fd = os.open(self.path, os.O_RDONLY | os.O_NONBLOCK)
        if self.grab:
            try:
                import fcntl
                fcntl.ioctl(fd, _EVIOCGRAB, 1)
            except OSError as ex:
                log.error("Could not grab %s exclusively (%s): the digits may ALSO be typed into whatever has focus.",
                          self.path, ex)
        return fd

    def run(self):
        own_fd = self._fd is None
        while not self.stop_event.is_set():
            try:
                fd = self._fd if self._fd is not None else self._open()
            except OSError as ex:
                self.connected = False
                log.warning("USB keyboard %s not available (%s); retrying.", self.path, ex)
                self.stop_event.wait(self.retry_s)
                continue
            self.connected = True
            log.info("USB keyboard %s open: swipes go to reader %s", self.path or "(descriptor)", self.reader)
            try:
                self._read(fd)
            except OSError as ex:
                log.warning("USB keyboard %s lost (%s).", self.path, ex)
            finally:
                self.connected = False
                if own_fd:
                    self._fd = None
                    try:
                        os.close(fd)
                    except OSError:
                        pass
            if not own_fd:
                return                               # an injected descriptor is not reopened
            self.stop_event.wait(self.retry_s)

    def _read(self, fd):
        buf = b""
        while not self.stop_event.is_set():
            ready, _, _ = select.select([fd], [], [], 0.05)
            if ready:
                chunk = os.read(fd, _EVENT.size * 64)
                if not chunk:
                    return                           # end of stream
                buf += chunk
                now = self.clock()
                while len(buf) >= _EVENT.size:
                    _, _, etype, code, value = _EVENT.unpack(buf[:_EVENT.size])
                    buf = buf[_EVENT.size:]
                    if etype == _EV_KEY:
                        self.emit(self.decoder.feed(code, value, now))
            self.emit(self.decoder.tick(self.clock()))


class SerialSource(_Source):
    """A CDC serial port. Needs pyserial (pip install pyserial); without it this source logs once and stays off."""
    kind = "serial"

    def __init__(self, device, reader, sink, path, baud=9600, idle_ms=200, clock=_now_ms, retry_s=5.0, opener=None):
        super().__init__(device, reader, sink, clock)
        self.path, self.baud, self.retry_s, self._opener = path, baud, retry_s, opener
        self.decoder = TextDecoder(idle_ms)

    def _open(self):
        if self._opener:
            return self._opener(self.path, self.baud)
        import serial  # optional dependency
        return serial.Serial(self.path, self.baud, timeout=0.05)

    def run(self):
        while not self.stop_event.is_set():
            try:
                port = self._open()
            except ImportError:
                log.error("pyserial is not installed: serial source %s is OFF (pip install pyserial).", self.path)
                return
            except Exception as ex:                  # SerialException, OSError
                self.connected = False
                log.warning("Serial port %s not available (%s); retrying.", self.path, ex)
                self.stop_event.wait(self.retry_s)
                continue
            self.connected = True
            log.info("Serial port %s open at %s baud: swipes go to reader %s", self.path, self.baud, self.reader)
            try:
                while not self.stop_event.is_set():
                    data = port.read(64)
                    if data:
                        self.emit(self.decoder.feed(data, self.clock()))
                    self.emit(self.decoder.tick(self.clock()))
            except Exception as ex:
                log.warning("Serial port %s lost (%s).", self.path, ex)
            finally:
                self.connected = False
                try:
                    port.close()
                except Exception:
                    pass
            self.stop_event.wait(self.retry_s)


class StdinSource(_Source):
    """Demo: type a credential number and press Enter in the terminal the gateway runs in."""
    kind = "stdin"

    def __init__(self, device, reader, sink, stream=None, idle_ms=200, clock=_now_ms):
        super().__init__(device, reader, sink, clock)
        self.stream = stream if stream is not None else sys.stdin
        self.decoder = TextDecoder(idle_ms)

    def run(self):
        self.connected = True
        try:
            for line in self.stream:
                if self.stop_event.is_set():
                    break
                self.emit(self.decoder.feed(line, self.clock()))
        except (OSError, ValueError):
            pass
        finally:
            self.connected = False


# --------------------------------------------------------------------------------------------------------
# LOCAL_INPUTS parsing
# --------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class InputSpec:
    kind: str          # kbd | serial | stdin
    path: str          # device path ("" for stdin)
    baud: int
    reader: str        # the Safetrust reader this USB device stands next to
    device: str        # label stored with every swipe, e.g. kbd-event5


def parse_inputs(text: str):
    """"kbd:/dev/input/event5=DrC0F8,serial:/dev/ttyACM0@115200=DrC1C4,stdin=DrC0F8" -> [InputSpec, ...]
    The name on the right is the Safetrust READER the USB device stands next to: the cloud then treats a USB swipe
    exactly like a swipe at that reader (same gate, same side)."""
    specs = []
    for part in (p.strip() for p in (text or "").split(",")):
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"LOCAL_INPUTS entry '{part}' needs =ReaderName (the Safetrust reader it stands next to)")
        left, reader = (s.strip() for s in part.rsplit("=", 1))
        if not reader:
            raise ValueError(f"LOCAL_INPUTS entry '{part}' has an empty reader name")
        kind, _, rest = left.partition(":")
        kind = kind.strip().lower()
        if kind not in ("kbd", "serial", "stdin"):
            raise ValueError(f"LOCAL_INPUTS kind '{kind}' is not one of kbd, serial, stdin")
        path, baud = rest.strip(), 9600
        if kind == "serial" and "@" in path:
            path, _, b = path.rpartition("@")
            baud = int(b)
        if kind != "stdin" and not path:
            raise ValueError(f"LOCAL_INPUTS entry '{part}' needs a device path after '{kind}:'")
        device = kind if kind == "stdin" else f"{kind}-{os.path.basename(path)}"
        specs.append(InputSpec(kind, path, baud, reader, device))
    return specs


def build_source(spec: InputSpec, sink, idle_ms: int = 200, grab: bool = True):
    if spec.kind == "kbd":
        return KeyboardSource(spec.device, spec.reader, sink, path=spec.path, grab=grab, idle_ms=idle_ms)
    if spec.kind == "serial":
        return SerialSource(spec.device, spec.reader, sink, spec.path, spec.baud, idle_ms=idle_ms)
    return StdinSource(spec.device, spec.reader, sink, idle_ms=idle_ms)
