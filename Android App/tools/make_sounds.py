"""Builds the Smart-Bin app's sounds into app/src/main/res/raw.

Every action in the app has its own short tune, and every kind of alert has
a longer one, so the phone can be told apart by ear. The rules follow the
Garbage-Guard app: rising pitch means forward, on, open or OK; falling pitch
means back, off, close or no. Button sounds are quiet, events louder, and
alerts loudest with a buzzier tone.

The SOUNDS table is plain data, so the ESP32 buzzer can later play the same
tunes for the same actions and the bin and the phone will sound alike.

Run from anywhere after changing the table:
    python3 "Android App/tools/make_sounds.py"
"""
import math
import struct
import wave
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"
RATE = 22050
ATTACK = 0.004    # seconds
RELEASE = 0.014   # seconds

# name: (group, notes). A note is (hz, ms), (start_hz, end_hz, ms) for a sweep, or (0, ms) for a rest.
SOUNDS = {
    # Keys and switches.
    "tap":         ("ui", [(1320, 22)]),
    "choice":      ("ui", [(1180, 16), (1480, 18)]),
    "switch_on":   ("ui", [(880, 40), (1320, 55)]),
    "switch_off":  ("ui", [(1320, 40), (880, 55)]),
    "step_up":     ("ui", [(1400, 24)]),
    "step_down":   ("ui", [(1100, 24)]),
    "tab":         ("ui", [(990, 26), (0, 10), (1245, 30)]),
    "open":        ("ui", [(740, 35), (990, 50)]),
    "back":        ("ui", [(990, 35), (740, 50)]),
    "confirm":     ("ui", [(880, 50), (1175, 50), (1760, 90)]),
    "danger":      ("ui", [(440, 90), (0, 30), (440, 90)]),
    "denied":      ("ui", [(392, 70), (0, 40), (330, 120)]),
    "logo":        ("ui", [(784, 70), (988, 70), (1175, 70), (1568, 170)]),
    "theme_light": ("ui", [(1175, 45), (1568, 75)]),
    "theme_dark":  ("ui", [(784, 45), (587, 75)]),
    # Things that happen to a bin or to the link.
    "boot":        ("event", [(523, 80), (659, 80), (784, 80), (1047, 220)]),
    "saved":       ("event", [(1047, 60), (1319, 100)]),
    "link_up":     ("event", [(659, 70), (880, 70), (1319, 150)]),
    "link_lost":   ("event", [(880, 90), (659, 90), (440, 190)]),
    "cloud":       ("event", [(880, 60), (0, 30), (1047, 60), (0, 30), (784, 130)]),
    "lock":        ("event", [(660, 40), (0, 20), (440, 120)]),
    "unlock":      ("event", [(440, 40), (0, 20), (660, 120)]),
    "pickup":      ("event", [(523, 70), (659, 70), (784, 70), (659, 70), (784, 150)]),
    "done":        ("event", [(784, 80), (1047, 80), (1319, 80), (1568, 230)]),
    "calib":       ("event", [(988, 40), (0, 50), (988, 40), (0, 50), (988, 40), (0, 50), (1319, 130)]),
    "sim":         ("event", [(523, 60), (622, 60), (740, 60), (880, 140)]),
    "bin_lost":    ("event", [(740, 100), (0, 60), (554, 210)]),
    "bin_back":    ("event", [(554, 80), (740, 150)]),
    # The operator PIN: a dry tick per digit, a key-turn into operator, and a
    # falling bolt back to collector. A wrong PIN buzzes low; a lockout drops further.
    "pin_key":     ("ui", [(1660, 14)]),
    "pin_wrong":   ("event", [(349, 60), (0, 30), (349, 60), (0, 30), (262, 160)]),
    "pin_lockout": ("event", [(523, 260, 260), (0, 60), (196, 300)]),
    "op_in":       ("event", [(587, 50), (0, 25), (880, 50), (1175, 60), (1760, 160)]),
    "op_out":      ("event", [(1175, 50), (880, 50), (0, 25), (587, 140)]),
    # A bin confirming a command or not, Bluetooth pairing, the bin joining
    # Wi-Fi, and a new operator PIN: each its own, so none borrows another's.
    "cmd_ok":      ("event", [(1319, 50), (0, 25), (1760, 110)]),
    "cmd_fail":    ("event", [(659, 80), (0, 30), (523, 80), (0, 30), (392, 200)]),
    "bt_paired":   ("event", [(1175, 40), (1568, 40), (1175, 40), (1976, 140)]),
    "bt_wrong":    ("event", [(466, 50), (0, 25), (466, 50), (370, 140)]),
    "bt_blocked":  ("event", [(622, 180), (0, 50), (311, 320)]),
    "wifi_joined": ("event", [(880, 60), (1175, 60), (1480, 60), (1760, 180)]),
    "pin_set":     ("event", [(1047, 50), (1397, 50), (1047, 50), (1397, 120)]),
    # Notification sounds, one per kind of alert. They also play inside the app.
    "alert_full":   ("alert", [(880, 180), (0, 80), (880, 180), (0, 80), (880, 180), (0, 200), (1175, 420)]),
    "alert_tamper": ("alert", [(700, 1400, 340), (1400, 700, 340), (700, 1400, 340), (1400, 700, 340)]),
    "alert_sensor": ("alert", [(1047, 120), (784, 120), (1047, 120), (784, 260)]),
    "alert_lock":   ("alert", [(660, 70), (0, 30), (440, 220)]),
    "alert_pickup": ("alert", [(523, 90), (659, 90), (784, 90), (1047, 260)]),
    "alert_info":   ("alert", [(988, 90), (1319, 170)]),
}

LEVEL = {"ui": 0.5, "event": 0.72, "alert": 0.85}
# How much of the 2nd and 3rd harmonic each group carries. Alerts buzz a little, keys stay soft.
TIMBRE = {"ui": (0.18, 0.0), "event": (0.22, 0.05), "alert": (0.35, 0.22)}


def note(hz0, hz1, secs, level, timbre):
    n = int(RATE * secs)
    h2, h3 = timbre
    norm = 1 + h2 + h3
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / ATTACK) * min(1.0, (secs - t) / RELEASE)
        env *= math.exp(-1.6 * t / max(secs, 0.05))
        hz = hz0 + (hz1 - hz0) * (i / max(n - 1, 1))
        phase += 2 * math.pi * hz / RATE
        s = math.sin(phase) + h2 * math.sin(2 * phase) + h3 * math.sin(3 * phase)
        out.append(level * env * s / norm)
    return out


def render(group, notes):
    level, timbre = LEVEL[group], TIMBRE[group]
    samples = []
    for n in notes:
        if len(n) == 3:
            samples += note(n[0], n[1], n[2] / 1000.0, level, timbre)
        elif n[0] == 0:
            samples += [0.0] * int(RATE * n[1] / 1000.0)
        else:
            samples += note(n[0], n[0], n[1] / 1000.0, level, timbre)
    return samples + [0.0] * int(RATE * 0.012)


def write(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (group, notes) in SOUNDS.items():
        write(OUT / f"sb_{name}.wav", render(group, notes))
    print(f"wrote {len(SOUNDS)} sounds to {OUT}")


if __name__ == "__main__":
    main()
