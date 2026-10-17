# Integrating the app with the real bins

For: Brian (mobile app). From: Gen (firmware and dashboard). Updated 17 Oct 2026.

The firmware in `firmware/SmartBin/` now runs on Bin 01 (ESP32, touchscreen, BME680, BMI160, ultrasonic). This file lists what the app already does right, what it needs to add, and how to test it. The web dashboard (`Web Dashboard/smartbin-dashboard.html`) already does everything below, so it is a working reference for each step.

## 1. Where things stand

| Part | State |
|---|---|
| Local Wi-Fi API (`/api/...`) | Working, tested on the real bin |
| Bluetooth Wi-Fi setup | Working, tested from the dashboard on Windows |
| Touch calibration | Working, tested on the real bin |
| Firebase | Written in the firmware, **not set up and not tested yet** (no project, no accounts) |
| ThingSpeak | Written in the firmware, **not set up and not tested yet** (no channels, no keys) |

So for now, test the app against the bin over the same Wi-Fi. Firebase and ThingSpeak come after section 5 is done.

## 2. What the app already does right (no change needed)

- Reads `GET /api/status` and sends `POST /api/command`, `/api/calibrate`, `/api/simulate` in the same format the firmware uses.
- Sends Firebase commands with `POST sb/commands/<cloud name>` and `{"cmd","ts","from"}`. The bin reads, deletes and runs them. Good.
- Reads `sb/bins/<cloud name>/status` and `sb/usage/bytes_month`. Both match what the bin writes.
- ThingSpeak: reads `feeds/last.json` and adds TalkBack commands with `command_string`. The field layout matches.

## 3. What to add, in order

### Step 1: Real Bluetooth Wi-Fi setup (replace the stand-in)

The bin now does real Bluetooth setup. The app's four steps stay the same; only the stand-in goes.

**How a person does it**
1. On the bin: tap **i** (top left) › **WI-FI SETUP** › type the **Collect PIN**. Bluetooth turns on and the bin shows `SmartBin-01`. It turns itself off after 5 minutes unused.
2. In the app: PAIR A BIN, pick `SmartBin-01`.
3. The bin shows a **6-digit code**. Android shows its own pairing dialog: the person types the code **there**. The app never sees the code.
4. The app reads the network list, the person picks one and types the password, the app sends it.
5. The bin joins and reports its IP. The app saves that IP as the bin's address.
6. The app sends Bluetooth off.

**Protocol**

| What | UUID | Access | Content |
|---|---|---|---|
| Service | `b1a70001-5c2e-4f8a-9d33-0a9c2b6f17c1` | advertised | Scan with a filter on this UUID. Name is `SmartBin-01` or `SmartBin-02`. |
| SSID | `b1a70002-…` | write | Network name, plain UTF-8 |
| PASS | `b1a70003-…` | write | Password, plain UTF-8 |
| CTRL | `b1a70004-…` | write | 1 byte: `1` = join with SSID and PASS, `2` = Bluetooth off |
| STAT | `b1a70005-…` | read, notify | JSON, see below |
| SCAN | `b1a70006-…` | read | JSON array, up to 6, strongest first: `[{"ssid":"HomeWiFi-2G","rssi":-48}, …]` |

(All UUIDs end in `-5c2e-4f8a-9d33-0a9c2b6f17c1`.)

STAT messages:
- `{"state":"WAITING"}`
- `{"state":"CONNECTING"}`
- `{"state":"CONNECTED","ip":"192.168.0.112","ssid":"HomeWiFi-2G"}`
- `{"state":"FAILED","why":"Wrong password"}`; the other reasons are `"Network not found"` and `"Could not join"`. After a failure the bin goes back to its old Wi-Fi by itself.

**Android notes**
- Every characteristic needs an encrypted, authenticated (passkey) link. Reading or writing any of them makes Android show the pairing dialog the first time, or call `createBond()` yourself first. Do not show your own code box.
- Permissions: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+), or `ACCESS_FINE_LOCATION` on older Android.
- Order: subscribe to STAT, read SCAN, write SSID, write PASS, write CTRL `1`, wait for a STAT with `CONNECTED` or `FAILED` (allow 20 s).
- If SCAN returns `[]`, the bin has not finished scanning: try again after 5 s. Always let the person type a name too (hidden networks).
- The bin now **remembers** a paired phone. The second time, Android reconnects without a code. If the phone was unpaired on one side only, the connection drops at once: tell the person to remove `SmartBin-01` in Android's Bluetooth settings and pair again. The dashboard retries 3 times before showing that message; doing the same is worth it.
- **Collectors and operators can both do Wi-Fi setup now.** Remove the operator check on these screens.
- Only 2.4 GHz networks work. For a phone hotspot: iPhone "Maximize Compatibility", Android "2.4 GHz band".

### Step 2: Read the new status fields

`GET /api/status` now also has these. The Firebase `sb/bins/<cloud name>/status` has the same, except `cal` and `touch` (the calibration is copied to `sb/bins/<cloud name>/cal` instead).

| Field | Meaning | What to do in the app |
|---|---|---|
| `id`, `name` | `bin01` / `BIN 01` | Match a bin to its card, suggest the cloud name |
| `faults` | Array of sensors not answering, e.g. `["HX711","HC-SR04"]` | Show "--" and a fault mark for those readings instead of 0 |
| `iaq_acc` | 0 = air sensor warming up (5 min after power-on), 1 to 3 = accuracy | While 0, do not show the air score |
| `gas_k` | Raw gas reading in kOhm | While `iaq_acc` is 0, show "Warming 142k" so people see it works |
| `iaq_warm_s` | Seconds left in the air sensor warm-up (only sent while `iaq_acc` is 0) | Show a countdown; an "Air sensor ready" alert comes when it ends |
| `tamper_phase` | `COUNTDOWN` or `LATCHED` | Countdown: 10 s, clears by itself. Latched: needs CLEAR_TAMPER |
| `touch` | `{swap,x0,x1,y0,y1,custom}` | Step 3 |
| `last_cmd` | `{cmd,ts,ok}` | Optional: show the last command result |
| `fw`, `rssi`, `uptime_s` | Firmware version, Wi-Fi signal, seconds since start | Optional: on the bin's info or Connection page |

### Step 3: Touch calibration buttons

The bin's touchscreen can be recalibrated from the app, like the dashboard's Settings > Touchscreen:
1. Show "Calibrated on the bin" when `touch.custom` is true, otherwise "Default values".
2. CALIBRATE TOUCH sends `{"cmd":"TOUCH_CAL"}`. The bin shows 4 crosses and the person presses them on the bin.
3. RESET TOUCH sends `{"cmd":"TOUCH_CAL_RESET"}`.
4. Local Wi-Fi only. Operator only, like the other calibration commands.

On the bin itself, holding a finger on the screen for 5 s starts the same calibration, so the app button is a convenience, not a must.

### Step 4: Confirm cloud commands (Firebase)

`POST sb/commands/<cloud name>` answers with `{"name":"<push key>"}`. The bin writes its answer to `sb/results/<push key>`:

```json
{"ok": true, "cmd": "LOCK", "ts": 1760690000000}
{"ok": false, "cmd": "COLLECT", "why": "TAMPER active", "ts": ...}
```

Poll that path every 1 s for up to 8 s, show "confirmed" or the `why`, then `DELETE` it. If no result comes, fall back to watching `status` (LOCKED, COLLECTING, ...), as the app does now. Other reasons: `"Too old, ignored"` (over 60 s) and `"Needs the bin's Wi-Fi"` (anything but LOCK, UNLOCK, COLLECT).

### Step 5: Tell the bins someone is watching (Firebase)

The bin sends Firebase updates every 5 s while someone watches and every 60 s otherwise, to stay under the free 10 GB a month. While the app is open and reading a bin over Firebase, write every 10 s:

```
PUT sb/watchers/<random id kept on the phone>
{"ts": {".sv": "timestamp"}, "kind": "app", "device": "<phone model>", "mode": "foreground", "bin": "all"}
```

and `DELETE` it when the app goes to the background. Tell me when this is in. Right now the firmware treats everyone as watching (`FB_REQUIRE_WATCHERS 0` in `config.h`). Once the app writes watchers, I switch it to 1 and the data use drops.

### Step 6 (optional): Bin alerts over Firebase

The bin writes each alert to `sb/alerts/<cloud name>` (newest 50 kept):

```json
{"kind":"tamper","sev":"critical","title":"Tamper detected","meta":"BIN 01 was moved or tilted (42 deg)","ts":...}
```

Kinds match the app's alert filters: full, sensor, tamper, lock, pickup, setup. Reading these lets the app show alerts that happened while it was away from the bin's Wi-Fi.

### Step 7 (optional): DEMO switch like the dashboard

The dashboard now has a DEMO button. Off shows only real bins, on adds three "Demo Bin" cards. The app's demo bins work fine as they are; this only keeps the two looking the same.

## 4. Things to know

- **Use the IP address, not `smartbin-01.local`.** Many Android phones cannot resolve `.local` names. The IP is on the bin's Info screen (tap **i**) and in the CONNECTED message after Bluetooth setup.
- **Bin numbers:** Bin 01 is `bin01`, `BIN 01`, `SmartBin-01`, `smartbin-01`; Bin 02 uses `02`. Set by `BIN_NUMBER` in `firmware/SmartBin/config.h`.
- **Calibration values** are in `cal` inside `/api/status`, and in `sb/bins/<cloud name>/cal` on Firebase. They include `collect_pin`, so only show them to people allowed to see it.
- **Commands** the bin accepts over Wi-Fi: LOCK, UNLOCK, CLEAR_TAMPER, COLLECT, COLLECT_DONE, CALIBRATE_START, CALIBRATE_END, TARE, CAL_WEIGHT (`"grams"`), RESET_GAS, BUZZ_TEST (`"sound"`), TOUCH_CAL, TOUCH_CAL_RESET. A refusal comes back as HTTP 409 `{"ok":false,"why":"..."}`.
- `CALIBRATE_START` also saves the bin's current tilt as "upright". Only start calibration with the bin standing straight.

## 5. Setting up Firebase and ThingSpeak (not done yet)

Gen does the firmware side; the app only needs the account and keys afterwards.

**Firebase** (`firmware/README.md` section 4 has every click):
1. New Firebase project, Realtime Database in `asia-southeast1`, paste `firmware/firebase.rules.json` in Rules.
2. Authentication › Email/Password on. Users: one per bin with an email starting with `bin` (`bin01@smartbin.test`), one per person.
3. In the database, `sb_users/<uid of each person>` = `true`.
4. Bins get the URL, Web API key and their own `bin…` account (in `secrets.h`, or from the dashboard's SEND TO BINS).
5. The app gets the same URL and key plus a **person's** account (`local.properties` or the Connection page).

**ThingSpeak** (`firmware/README.md` section 5):
1. One channel per bin, 8 fields as in the app README.
2. Write keys go into the firmware's `secrets.h`. Channel ID and read key go into the app's SET sheet.
3. Optional TalkBack per bin for LOCK, UNLOCK, COLLECT.

## 6. Testing checklist

| # | Test | Pass |
|---|---|---|
| 1 | Phone and bin on the same Wi-Fi, bin's IP in its SET sheet | Card shows live readings, no DEMO |
| 2 | LOCK then UNLOCK from the app | Bin's screen changes within 1 s |
| 3 | Unplug a sensor wire | That reading shows a fault, not 0 |
| 4 | Restart the bin | Air shows "Warming", then a score after about 5 min |
| 5 | Bluetooth setup to a phone hotspot | Bin joins, the app saves the new IP, Bluetooth goes off |
| 6 | Bluetooth setup a second time from the same phone | No code asked, still works |
| 7 | CALIBRATE TOUCH | Bin shows crosses; afterwards status says calibrated |
| 8 | After Firebase is set up: phone on mobile data, press LOCK | Confirmed within 8 s through Firebase |
| 9 | After ThingSpeak is set up: phone on mobile data, Firebase off | Readings from ThingSpeak, LOCK through TalkBack within 15 s |

Without the bin, `tools/smartbin_mock_server.py` still covers tests 1 to 3. It has no Bluetooth; test steps 5 and 6 on the real bin.

Questions: ask Gen. The firmware details are in `firmware/README.md`, and the dashboard code shows a working version of each step.
