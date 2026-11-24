"""Tests for the USB input side: credential parsing, the keyboard/serial decoders and the source threads.
No hardware is needed: key events are written into a pipe in the same binary layout Linux evdev uses.
Run with:  python -m pytest -q edge_gateway"""
import io
import os
import struct
import sys
import threading
import time

import pytest

sys.path.insert(0, os.path.dirname(__file__))
import usb_input as usb  # noqa: E402
from usb_input import KeyboardDecoder, RawSwipe, TextDecoder, parse_credential  # noqa: E402

# Linux key codes (linux/input-event-codes.h)
KEY = {"1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11}
KP = {"1": 79, "2": 80, "3": 81, "4": 75, "5": 76, "6": 77, "7": 71, "8": 72, "9": 73, "0": 82}
ENTER, KPENTER, LSHIFT, LCTRL, F1, KEY_A, KEY_F = 28, 96, 42, 29, 59, 30, 33


def type_keys(dec, codes, t=1000, step=0):
    out = []
    for i, code in enumerate(codes):
        out += dec.feed(code, 1, t + i * step)
        out += dec.feed(code, 0, t + i * step)
    return out


# ---- parse_credential ---------------------------------------------------------------------------------
@pytest.mark.parametrize("text,expected", [
    ("38", "38"), ("1005", "1005"), (" 1005\n", "1005"), ("0038", "38"), ("0", "0"), ("000", "0"), (38, "38"),
    ("123456789012", "123456789012"),
])
def test_a_credential_number_is_kept_without_leading_zeros(text, expected):
    assert parse_credential(text) == expected


@pytest.mark.parametrize("text", [None, "", "  ", "abc", "38a", "3 8", "-5", "3.8", "38?", "1234567890123", "0x26", "٣٨"])
def test_anything_that_is_not_a_plain_number_is_refused(text):
    assert parse_credential(text) is None


# ---- keyboard decoder ---------------------------------------------------------------------------------
def test_a_swipe_ends_at_enter_and_carries_the_time_of_its_first_key():
    dec = KeyboardDecoder()
    assert type_keys(dec, [KEY["3"], KEY["8"]], t=5000, step=10) == []
    assert dec.feed(ENTER, 1, 5030) == [RawSwipe("38", 5000)]


def test_the_keypad_and_keypad_enter_work_the_same():
    dec = KeyboardDecoder()
    type_keys(dec, [KP["1"], KP["0"], KP["0"], KP["5"]])
    assert dec.feed(KPENTER, 1, 1100) == [RawSwipe("1005", 1000)]


def test_a_reader_that_sends_no_enter_is_flushed_after_the_idle_gap():
    dec = KeyboardDecoder(idle_ms=200)
    type_keys(dec, [KEY["3"], KEY["8"]], t=1000, step=5)
    assert dec.tick(1100) == []                       # still inside the burst
    assert dec.tick(1205) == [RawSwipe("38", 1000)]   # last key at 1005, 200 ms of silence
    assert dec.tick(5000) == []                       # nothing left


def test_two_swipes_without_enter_are_two_swipes_when_a_pause_separates_them():
    dec = KeyboardDecoder(idle_ms=200)
    first = type_keys(dec, [KEY["3"], KEY["8"]], t=1000, step=5)
    second = type_keys(dec, [KEY["1"], KEY["0"], KEY["0"], KEY["5"]], t=3000, step=5)
    assert first == []                                   # nothing says the swipe is over yet...
    assert second == [RawSwipe("38", 1000)]              # ...until the pause is seen by the next swipe's first key
    assert dec.tick(4000) == [RawSwipe("1005", 3000)]    # (a live gateway flushes on the idle tick long before that)


def test_key_releases_and_auto_repeat_add_nothing():
    dec = KeyboardDecoder()
    dec.feed(KEY["3"], 1, 1000)
    dec.feed(KEY["3"], 2, 1010)       # auto-repeat
    dec.feed(KEY["3"], 2, 1020)
    dec.feed(KEY["3"], 0, 1030)
    assert dec.feed(ENTER, 1, 1040) == [RawSwipe("3", 1000)]


def test_enter_on_an_empty_buffer_makes_no_swipe():
    dec = KeyboardDecoder()
    assert dec.feed(ENTER, 1, 1000) == [] and dec.feed(KPENTER, 1, 1001) == []


def test_modifier_keys_print_nothing():
    dec = KeyboardDecoder()
    type_keys(dec, [LCTRL, KEY["3"], KEY["8"]])
    assert dec.feed(ENTER, 1, 2000) == [RawSwipe("38", 1000)]


def test_a_key_the_decoder_does_not_know_poisons_the_swipe_instead_of_vanishing():
    dec = KeyboardDecoder()
    type_keys(dec, [KEY["3"], F1, KEY["8"]])
    swipe = dec.feed(ENTER, 1, 2000)[0]
    assert swipe.text == "3?8" and parse_credential(swipe.text) is None     # refused later, not read as 38


def test_shift_plus_a_digit_is_a_symbol_not_a_digit():
    dec = KeyboardDecoder()
    dec.feed(LSHIFT, 1, 1000)
    dec.feed(KEY["3"], 1, 1001)
    dec.feed(LSHIFT, 0, 1002)
    dec.feed(KEY["8"], 1, 1003)
    swipe = dec.feed(ENTER, 1, 1004)[0]
    assert swipe.text == "?8" and parse_credential(swipe.text) is None


def test_letters_are_decoded_so_a_non_numeric_credential_is_refused_not_misread():
    dec = KeyboardDecoder()
    type_keys(dec, [KEY["3"], KEY_A, KEY["8"]])
    assert dec.feed(ENTER, 1, 2000)[0].text == "3a8"
    dec.feed(LSHIFT, 1, 3000); dec.feed(KEY_F, 1, 3001); dec.feed(LSHIFT, 0, 3002)
    assert dec.feed(ENTER, 1, 3003)[0].text == "F"


def test_runaway_input_is_handed_on_to_be_refused_and_the_next_swipe_starts_clean():
    dec = KeyboardDecoder(max_len=5)
    out = type_keys(dec, [KEY["1"]] * 6, t=1000, step=1)
    assert out == [RawSwipe("111111", 1000)] and parse_credential(out[0].text) == "111111"   # 6 digits is still a number
    out = type_keys(dec, [KEY["1"]] * 14, t=2000, step=1)
    assert len(out[0].text) == 6 and parse_credential("1" * 14) is None


# ---- text decoder (serial / stdin) --------------------------------------------------------------------
def test_text_lines_with_cr_lf_or_crlf_are_one_swipe_each():
    for terminator in ("\n", "\r", "\r\n"):
        assert TextDecoder().feed(f"38{terminator}", 1000) == [RawSwipe("38", 1000)], repr(terminator)


def test_two_swipes_in_one_chunk_and_a_chunk_split_in_the_middle():
    dec = TextDecoder()
    assert dec.feed(b"1005\r\n38\r\n", 1000) == [RawSwipe("1005", 1000), RawSwipe("38", 1000)]
    assert dec.feed("1", 2000) == [] and dec.feed(b"0\n", 2003) == [RawSwipe("10", 2000)]


def test_spaces_and_control_bytes_are_ignored_but_other_characters_are_kept_to_be_refused():
    dec = TextDecoder()
    assert dec.feed(b"\x02 38 \x03\n", 1000) == [RawSwipe("38", 1000)]
    assert dec.feed("3x8\n", 2000) == [RawSwipe("3x8", 2000)]


def test_text_without_a_terminator_is_flushed_after_the_idle_gap():
    dec = TextDecoder(idle_ms=200)
    assert dec.feed("38", 1000) == []
    assert dec.tick(1150) == [] and dec.tick(1200) == [RawSwipe("38", 1000)]


# ---- LOCAL_INPUTS --------------------------------------------------------------------------------------
def test_inputs_are_parsed_with_device_labels_and_the_reader_they_stand_next_to():
    specs = usb.parse_inputs("kbd:/dev/input/event5=DrC0F8, serial:/dev/ttyACM0@115200=DrC1C4 ,stdin=DrC0F8,serial:COM3=DrX")
    assert [(s.kind, s.path, s.baud, s.reader, s.device) for s in specs] == [
        ("kbd", "/dev/input/event5", 9600, "DrC0F8", "kbd-event5"),
        ("serial", "/dev/ttyACM0", 115200, "DrC1C4", "serial-ttyACM0"),
        ("stdin", "", 9600, "DrC0F8", "stdin"),
        ("serial", "COM3", 9600, "DrX", "serial-COM3"),
    ]
    assert usb.parse_inputs("") == [] and usb.parse_inputs(None) == []


@pytest.mark.parametrize("bad", ["kbd:/dev/input/event5", "kbd:/dev/input/event5=", "mouse:/dev/x=DrA", "kbd:=DrA", "serial:/dev/ttyACM0@fast=DrA"])
def test_a_wrong_inputs_setting_is_refused_at_start_up(bad):
    with pytest.raises(ValueError):
        usb.parse_inputs(bad)


# ---- sources -------------------------------------------------------------------------------------------
EVENT = struct.Struct("@llHHi")


def ev(etype, code, value):
    return EVENT.pack(0, 0, etype, code, value)


class Collector:
    def __init__(self):
        self.items, self.cond = [], threading.Condition()

    def __call__(self, reader, device, text, t_ms):
        with self.cond:
            self.items.append((reader, device, text, t_ms))
            self.cond.notify_all()

    def wait_for(self, n, secs=3.0):
        end = time.time() + secs
        with self.cond:
            while len(self.items) < n and time.time() < end:
                self.cond.wait(0.05)
        return list(self.items)


def test_the_keyboard_source_reads_binary_events_and_ignores_everything_that_is_not_a_key():
    r, w = os.pipe()
    sink = Collector()
    src = usb.KeyboardSource("kbd-event5", "DrC0F8", sink, fd=r, grab=False, clock=lambda: 7777)
    src.start()
    try:
        data = b""
        for ch in "1005":
            data += ev(1, KEY[ch], 1) + ev(1, KEY[ch], 0) + ev(0, 0, 0)       # key down, key up, EV_SYN report
        data += ev(4, 4, 70001)                                                # EV_MSC scancode
        data += ev(1, ENTER, 1) + ev(1, ENTER, 0)
        os.write(w, data)
        assert sink.wait_for(1) == [("DrC0F8", "kbd-event5", "1005", 7777)]
    finally:
        src.close(); os.close(w); src.join(2)
    assert not src.is_alive() and not src.connected


def test_the_keyboard_source_flushes_a_swipe_that_has_no_enter():
    r, w = os.pipe()
    sink = Collector()
    clock = {"t": 1000}
    src = usb.KeyboardSource("kbd-event5", "DrC0F8", sink, fd=r, grab=False, idle_ms=100, clock=lambda: clock["t"])
    src.start()
    try:
        os.write(w, ev(1, KEY["3"], 1) + ev(1, KEY["8"], 1))
        time.sleep(0.2)
        assert sink.items == []                        # the clock has not moved: still inside the burst
        clock["t"] = 1500
        assert sink.wait_for(1) == [("DrC0F8", "kbd-event5", "38", 1000)]
    finally:
        src.close(); os.close(w); src.join(2)


def test_a_half_event_split_across_two_reads_is_put_together():
    r, w = os.pipe()
    sink = Collector()
    src = usb.KeyboardSource("k", "DrA", sink, fd=r, grab=False, clock=lambda: 1)
    src.start()
    try:
        data = ev(1, KEY["7"], 1) + ev(1, ENTER, 1)
        os.write(w, data[:10]); time.sleep(0.1); os.write(w, data[10:])
        assert sink.wait_for(1) == [("DrA", "k", "7", 1)]
    finally:
        src.close(); os.close(w); src.join(2)


def test_a_sink_that_raises_does_not_stop_the_reader():
    r, w = os.pipe()
    seen = []

    def sink(reader, device, text, t_ms):
        seen.append(text)
        if text == "1":
            raise RuntimeError("boom")

    src = usb.KeyboardSource("k", "DrA", sink, fd=r, grab=False, clock=lambda: 1)
    src.start()
    try:
        os.write(w, ev(1, KEY["1"], 1) + ev(1, ENTER, 1) + ev(1, KEY["2"], 1) + ev(1, ENTER, 1))
        deadline = time.time() + 3
        while len(seen) < 2 and time.time() < deadline:
            time.sleep(0.02)
        assert seen == ["1", "2"]
    finally:
        src.close(); os.close(w); src.join(2)


def test_the_keyboard_source_keeps_trying_when_the_device_is_missing_and_stops_on_close():
    src = usb.KeyboardSource("k", "DrA", Collector(), path="/dev/input/does-not-exist", grab=False, retry_s=0.05)
    src.start()
    time.sleep(0.2)
    assert src.is_alive() and not src.connected
    src.close(); src.join(2)
    assert not src.is_alive()


class FakePort:
    def __init__(self, chunks):
        self.chunks, self.closed = list(chunks), False

    def read(self, n):
        if self.chunks:
            item = self.chunks.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        time.sleep(0.01)
        return b""

    def close(self):
        self.closed = True


def test_the_serial_source_reads_lines_and_reopens_the_port_after_an_error():
    sink = Collector()
    ports = [FakePort([b"38\r\n", OSError("unplugged")]), FakePort([b"1005\n"])]
    src = usb.SerialSource("serial-ttyACM0", "DrC1C4", sink, "/dev/ttyACM0", retry_s=0.05, clock=lambda: 4242,
                           opener=lambda path, baud: ports.pop(0))
    src.start()
    try:
        assert sink.wait_for(2) == [("DrC1C4", "serial-ttyACM0", "38", 4242), ("DrC1C4", "serial-ttyACM0", "1005", 4242)]
    finally:
        src.close(); src.join(2)


def test_the_serial_source_without_pyserial_stays_off_without_crashing_the_gateway():
    def no_pyserial(path, baud):
        raise ImportError("No module named 'serial'")
    src = usb.SerialSource("s", "DrA", Collector(), "/dev/ttyACM0", opener=no_pyserial)
    src.start(); src.join(2)
    assert not src.is_alive()


def test_the_stdin_source_turns_typed_lines_into_swipes_and_ends_at_end_of_input():
    sink = Collector()
    src = usb.StdinSource("stdin", "DrC0F8", sink, stream=io.StringIO("38\n\n1005\nabc\n"), clock=lambda: 9)
    src.start(); src.join(2)
    assert sink.items == [("DrC0F8", "stdin", "38", 9), ("DrC0F8", "stdin", "1005", 9), ("DrC0F8", "stdin", "abc", 9)]
    assert not src.connected


# ---- a reader that cannot be listened to says why (found on a Mac: kbd: reads /dev/input, which is Linux only) ----------
def test_keyboard_source_on_a_mac_says_it_is_linux_only_and_does_not_retry_forever(monkeypatch, caplog):
    import usb_input
    monkeypatch.setattr(usb_input.sys, "platform", "darwin")
    seen = []
    src = usb_input.KeyboardSource("kbd-event5", "F9E2", lambda *a: seen.append(a), path="/dev/input/event5")
    src.run()                                                       # returns at once instead of retrying every 5 s
    assert src.connected is False and "Linux only" in src.note and "macOS" in src.note and "stdin=" in src.note
    assert "serial:" in src.note and "cu.usbmodem" in src.note
    assert any("USB input kbd-event5" in r.message for r in caplog.records)


def test_keyboard_source_on_linux_explains_a_missing_device(monkeypatch):
    import usb_input
    monkeypatch.setattr(usb_input.sys, "platform", "linux")
    src = usb_input.KeyboardSource("kbd-x", "F9E2", lambda *a: None, path="/dev/input/by-id/does-not-exist", retry_s=0.01)
    t = threading.Thread(target=src.run, daemon=True)
    t.start()
    time.sleep(0.2)
    src.close()
    t.join(2)
    assert src.connected is False and "by-id/does-not-exist" in src.note and "ls -l /dev/input/by-id/" in src.note


def test_serial_source_names_the_missing_port():
    import usb_input

    def opener(path, baud):
        raise OSError(2, "No such file or directory")

    src = usb_input.SerialSource("serial-x", "F9E2", lambda *a: None, "/dev/cu.usbmodem1101", 9600, retry_s=0.01, opener=opener)
    t = threading.Thread(target=src.run, daemon=True)
    t.start()
    time.sleep(0.2)
    src.close()
    t.join(2)
    assert "cu.usbmodem1101" in src.note and "not available" in src.note
