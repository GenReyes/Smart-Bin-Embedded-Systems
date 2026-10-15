#!/usr/bin/env python3
"""
Stand-in Smart-Bin bins for testing the Android app with no hardware.

Each bin answers the same local REST API the ESP32 firmware will serve:

    GET  /api/status     readings, status, calibration and simulation mask
    POST /api/command    {"cmd": "LOCK"} and the other commands
    POST /api/calibrate  calibration values
    POST /api/simulate   {"mask": 5, "values": {...}}

Optionally it also plays a small Firebase Realtime Database on another port,
with the same paths the app uses for cloud mode:

    /sb/bins/<id>/status.json    GET   the bin's last status, ts and local_ip
    /sb/commands/<id>.json       POST  LOCK, UNLOCK or COLLECT
    /sb/usage/bytes_month.json   GET
    /sb_users/<uid>.json         GET   the allowed-user check
    /__auth/signInWithPassword   POST  sign-in, any email and a password of 6+ characters

Plain Python 3, no packages. Run it on a computer on the same Wi-Fi as the phone:

    python smartbin_mock_server.py                 two bins on ports 8081 and 8082
    python smartbin_mock_server.py --bins 3        three bins, 8081 to 8083
    python smartbin_mock_server.py --cloud 8090    also the fake Firebase on 8090

In the app, open a bin with SET and type <computer IP>:8081 as its address.
For cloud mode, use http://<computer IP>:8090 as the database URL, any API
key, any email and a 6+ character password, then stop the bins with
--no-local so only the cloud answers.
"""

import argparse
import json
import random
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

DEFAULT_CAL = {
    "d_empty": 30, "d_full": 5, "fill_th": 80, "temp_th": 40, "hum_th": 85, "gas_th": 200,
    "tilt_th": 30, "weight_cap": 4500, "auto_lock": 0, "servo_closed": 0, "servo_open": 90,
    "collect_pin": 1234, "pir_hold_s": 5, "buzz_s": 2, "temp_off": -2, "hum_off": 0, "press_off": 0,
}
DEFAULT_SIM = {"fill": 60, "weight": 2000, "gas": 120, "temp": 28, "humidity": 60, "tamper": 0, "motion": 0, "lid": 0}


class Bin:
    """One bin, stepped once a second the same way the app's demo mode steps it."""

    def __init__(self, index, port):
        self.id = "bin%02d" % (index + 1)
        self.port = port
        self.lock = threading.Lock()
        self.cal = dict(DEFAULT_CAL)
        self.sim_mask = 0
        self.sim = dict(DEFAULT_SIM)
        self.fill = [34.0, 62.0, 18.0][index % 3]
        self.rate = [0.38, 3.1, 0.9][index % 3] * 60  # percent per hour, sped up 60 times
        self.temp_raw = 30.0
        self.hum = 62.0
        self.press = 1008.4
        self.gas = 60 + self.fill
        self.iaq_acc = 3
        self.tilt = 0.0
        self.motion = False
        self.lid_open = False
        self.status = "OK"
        self.calibrating = False

    def on(self, bit):
        return (self.sim_mask >> bit) & 1 == 1

    def step(self):
        with self.lock:
            c, s = self.cal, self.sim
            if self.calibrating:
                self.status = "CALIBRATING"
                self.lid_open = False
                return
            if self.status == "CALIBRATING":
                self.status = "OK"
            if self.on(0):
                self.fill = float(s["fill"])
            elif self.status != "COLLECTING":
                self.fill = min(100.0, self.fill + self.rate / 3600 * random.uniform(0.7, 1.3))
            if self.on(2):
                self.gas = float(s["gas"])
            else:
                self.gas += ((35 + self.fill * 2.2) - self.gas) * 0.02 + random.uniform(-2.5, 2.5)
            if not self.on(3):
                self.temp_raw = min(34, max(24, self.temp_raw + random.uniform(-0.04, 0.04)))
                self.hum = min(95, max(35, self.hum + random.uniform(-0.3, 0.3)))
            self.press += random.uniform(-0.03, 0.03)
            self.tilt = 38.0 if self.on(4) and s["tamper"] else random.uniform(0, 2.2)
            self.motion = bool(s["motion"]) if self.on(5) else random.random() < 0.025
            if self.status == "COLLECTING":
                self.lid_open = True
            elif self.tilt > c["tilt_th"]:
                self.status, self.lid_open = "TAMPER", False
            elif self.status in ("TAMPER", "LOCKED"):
                self.lid_open = False
            elif c["auto_lock"] and self.fill >= c["fill_th"]:
                self.status, self.lid_open = "LOCKED", False
            else:
                self.lid_open = bool(s["lid"]) if self.on(6) else self.motion

    def status_json(self):
        with self.lock:
            c, s = self.cal, self.sim
            temp = float(s["temp"]) if self.on(3) else self.temp_raw + c["temp_off"]
            hum = float(s["humidity"]) if self.on(3) else self.hum
            weight = float(s["weight"]) if self.on(1) else round(self.fill / 100 * c["weight_cap"] * 0.92 + random.uniform(0, 25))
            distance = c["d_empty"] - self.fill / 100 * (c["d_empty"] - c["d_full"])
            return {
                "fill": round(self.fill, 1), "distance_cm": round(distance, 1), "weight_g": weight,
                "temp": round(temp, 2), "temp_raw": round(temp - c["temp_off"], 2), "humidity": round(hum, 1),
                "pressure": round(self.press, 1), "iaq": round(self.gas), "iaq_acc": self.iaq_acc,
                "tilt": round(self.tilt, 1), "motion": self.motion, "lid": "OPEN" if self.lid_open else "CLOSED",
                "status": self.status, "cal": c, "sim_mask": self.sim_mask,
            }

    def command(self, cmd, body):
        with self.lock:
            if cmd == "LOCK":
                self.status, self.lid_open = "LOCKED", False
            elif cmd == "UNLOCK":
                self.status = "OK"
            elif cmd == "CLEAR_TAMPER":
                self.status, self.tilt = "OK", 0.0
                self.sim["tamper"] = 0
            elif cmd == "COLLECT":
                self.status, self.lid_open = "COLLECTING", True
            elif cmd == "COLLECT_DONE":
                self.status, self.fill, self.lid_open = "OK", 0.0, False
                self.gas = min(self.gas, 48)
            elif cmd == "CALIBRATE_START":
                self.calibrating = True
            elif cmd == "CALIBRATE_END":
                self.calibrating = False
            elif cmd == "RESET_GAS":
                self.iaq_acc = 0
            elif cmd in ("TARE", "CAL_WEIGHT"):
                pass
            else:
                return False
            print("  %s: %s" % (self.id, cmd), flush=True)
            return True


def local_handler(b):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, obj):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return {}

        def do_GET(self):
            if urlparse(self.path).path == "/api/status":
                self.send(200, b.status_json())
            else:
                self.send(404, {"error": "not found"})

        def do_POST(self):
            p = urlparse(self.path).path
            j = self.body()
            if p == "/api/command":
                ok = b.command(str(j.get("cmd", "")), j)
                self.send(200 if ok else 400, {"ok": ok})
            elif p == "/api/calibrate":
                with b.lock:
                    for k, v in j.items():
                        if k in b.cal:
                            b.cal[k] = v
                print("  %s: calibration saved" % b.id, flush=True)
                self.send(200, {"ok": True})
            elif p == "/api/simulate":
                with b.lock:
                    b.sim_mask = int(j.get("mask", 0))
                    b.sim.update(j.get("values", {}))
                print("  %s: simulation mask %d" % (b.id, b.sim_mask), flush=True)
                self.send(200, {"ok": True})
            else:
                self.send(404, {"error": "not found"})

    return H


def cloud_handler(bins, ip):
    by_id = {b.id: b for b in bins}
    sent = {"bytes": 0}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, obj):
            data = json.dumps(obj).encode()
            sent["bytes"] += len(data)
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return {}

        def do_GET(self):
            u = urlparse(self.path)
            parts = u.path.strip("/").removesuffix(".json").split("/")
            if parts[:2] == ["sb", "bins"] and len(parts) == 2:
                self.send(200, {k: True for k in by_id})
            elif parts[:2] == ["sb", "bins"] and len(parts) == 4 and parts[3] == "status" and parts[2] in by_id:
                b = by_id[parts[2]]
                s = b.status_json()
                s["ts"] = int(time.time() * 1000)
                s["local_ip"] = "%s:%d" % (ip, b.port)
                self.send(200, s)
            elif parts == ["sb", "usage", "bytes_month"]:
                self.send(200, sent["bytes"])
            elif parts[0] == "sb_users":
                self.send(200, {"role": "collector"})
            else:
                self.send(200, None)

        def do_POST(self):
            u = urlparse(self.path)
            if u.path.startswith("/__auth/signInWithPassword"):
                j = self.body()
                if "@" not in str(j.get("email", "")) or len(str(j.get("password", ""))) < 6:
                    self.send(400, {"error": {"message": "INVALID_LOGIN_CREDENTIALS"}})
                else:
                    self.send(200, {"idToken": "mock-token", "localId": "mock-user", "expiresIn": "3600"})
                return
            parts = u.path.strip("/").removesuffix(".json").split("/")
            if parts[:2] == ["sb", "commands"] and len(parts) == 3 and parts[2] in by_id:
                cmd = str(self.body().get("cmd", ""))
                ok = cmd in ("LOCK", "UNLOCK", "COLLECT") and by_id[parts[2]].command(cmd, {})
                self.send(200 if ok else 400, {"name": "-mock%d" % int(time.time())} if ok else {"error": "Permission denied"})
            else:
                self.send(404, {"error": "not found"})

    return H


def my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description="Stand-in Smart-Bin bins for the Android app.")
    ap.add_argument("--bins", type=int, default=2, help="how many bins (default 2)")
    ap.add_argument("--port", type=int, default=8081, help="port of the first bin (default 8081)")
    ap.add_argument("--cloud", type=int, default=0, help="also serve a fake Firebase on this port")
    ap.add_argument("--no-local", action="store_true", help="do not answer on the bin ports, to test cloud mode")
    a = ap.parse_args()

    ip = my_ip()
    bins = [Bin(i, a.port + i) for i in range(max(1, a.bins))]
    servers = []
    if not a.no_local:
        for b in bins:
            servers.append(ThreadingHTTPServer(("0.0.0.0", b.port), local_handler(b)))
            print("%s answers at %s:%d" % (b.id, ip, b.port))
    if a.cloud:
        servers.append(ThreadingHTTPServer(("0.0.0.0", a.cloud), cloud_handler(bins, ip)))
        print("Fake Firebase at http://%s:%d" % (ip, a.cloud))
    for s in servers:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("Press Ctrl+C to stop.", flush=True)
    try:
        while True:
            for b in bins:
                b.step()
            time.sleep(1)
    except KeyboardInterrupt:
        for s in servers:
            s.shutdown()


if __name__ == "__main__":
    main()
