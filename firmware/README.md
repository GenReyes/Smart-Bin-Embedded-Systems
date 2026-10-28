# Smart-Bin ESP32 firmware

One sketch runs each bin. It reads the sensors, drives the lid, buzzer, RGB LED and the 2.4 in touchscreen, serves the local API the dashboard and the Android app use, and copies the bin's status to Firebase and ThingSpeak.

It follows `Web Dashboard/ESP32-Integration-Guide.md` (the API, Firebase and ThingSpeak layout the app v1.2 already reads) and `Web Dashboard/Interrupt-Driven-Design-Guide.md` (tasks and interrupts). The screens follow `Mockups/smartbin-touchscreen-mockup v4.html`.

> **Status:** it compiles cleanly for the ESP32 (Arduino core 3.3.12, all libraries below). It has **not been run on the real bins yet**, so expect to tune touch calibration, the servo angles and the load-cell scale on the bench.

## Folder

| File | What it does |
|---|---|
| `SmartBin/SmartBin.ino` | Starts everything |
| `SmartBin/config.h` | Pins, timings, which bin this is (`BIN_NUMBER`) |
| `SmartBin/secrets.example.h` | Template for your Wi-Fi, Firebase and ThingSpeak keys |
| `SmartBin/state.*` | The shared bin state, calibration saved in flash, the status JSON |
| `SmartBin/sensors.*` | HC-SR04, HX711, BME680, BMI160 |
| `SmartBin/control.*` | Lid, lock, collect, tamper countdown, auto-lock, alarms, RGB LED, commands |
| `SmartBin/buzzer.*` | Every tune from Integration Guide 9.2, with the priority rules |
| `SmartBin/net.*` | Wi-Fi, `smartbin-01.local`, the local API, push updates (`/api/events`) |
| `SmartBin/cloud.*` | Firebase (status, commands, results, alerts, data meter) and ThingSpeak (fields, TalkBack) |
| `SmartBin/ui.*` | The touchscreen screens A1 to A10 and touch |
| `SmartBin/ble_setup.*` | Wi-Fi setup over Bluetooth with a 6-digit pairing code on the screen |
| `SmartBin/dashboard_gz.h` | The web dashboard, packed in, so `http://<bin-ip>/` opens it |
| `tools/embed_dashboard.py` | Rebuilds `dashboard_gz.h` after the dashboard changes |
| `firebase.rules.json` | Rules to paste into the Firebase console |

## 1. Install the tools (once)

1. Install **Arduino IDE 2**.
2. **File > Preferences > Additional boards manager URLs**, add `https://espressif.github.io/arduino-esp32/package_esp32_index.json`.
3. **Tools > Board > Boards Manager**, install **esp32 by Espressif**, version **3.3.x** (3.3.12 was used to compile this).
4. **Sketch > Include Library > Manage Libraries**, install these:

| Library | Author | Version used |
|---|---|---|
| ESP Async WebServer | ESP32Async | 3.x |
| Async TCP | ESP32Async | 3.x |
| ArduinoJson | Benoit Blanchon | 7.x |
| Adafruit ILI9341 | Adafruit | 1.6.x (also installs Adafruit GFX and BusIO) |
| XPT2046_Touchscreen | Paul Stoffregen | 1.4 |
| Adafruit BME680 Library | Adafruit | 2.0.x (also installs Adafruit Unified Sensor) |
| HX711 Arduino Library | Bogdan Necula | 0.7.x |
| ESP32Servo | Kevin Harrington | 3.x |
| NimBLE-Arduino | h2zero | **2.x** (not the 3.0 preview) |

## 2. Upload to a bin

1. Open `firmware/SmartBin/SmartBin.ino`.
2. Copy `secrets.example.h` to `secrets.h` **in the same folder** and fill in your Wi-Fi. Leave Firebase and ThingSpeak empty for the first test. `secrets.h` is git-ignored, so it never reaches GitHub.
3. In `config.h`, set `#define BIN_NUMBER 1` for Bin A or `2` for Bin B.
4. **Tools** menu:
   - Board: **ESP32 Dev Module**
   - Partition Scheme: **Huge APP (3MB No OTA/1MB SPIFFS)**. The default partition is too small for Wi-Fi + Bluetooth + the dashboard.
   - Upload Speed: 921600 (lower it to 460800 if uploads fail)
5. Upload, then open the **Serial Monitor at 115200**. You should see:

```
Smart-Bin bin01 firmware v1.0.0
[wifi] 192.168.1.101  http://smartbin-01.local
```

6. Open `http://192.168.1.101/` (the IP the screen and the Serial Monitor show) in a browser. The dashboard opens and reads that bin live.

Do the same for the second bin with `BIN_NUMBER 2`.

## 3. Calibrate on the bench

| What | How |
|---|---|
| Touch | Set `TOUCH_DEBUG 1` in `config.h`, upload, press the four corners and read the raw values in the Serial Monitor. Put them in `TS_RAW_X0/X1/Y0/Y1`. If up/down moves left/right, set `TS_SWAP_XY 1`. Set `TOUCH_DEBUG 0` again. |
| Servo | Dashboard **Settings**, calibration on, change *Servo closed* and *Servo open* until the lid shuts and opens fully. |
| Fill | Empty bin: press USE NOW for *Distance empty*. Put something at the full line: USE NOW for *Distance full*. Save. |
| Weight | Empty bin: TARE. Put a known weight in (for example 500 g): type it, CALIBRATE. |
| Tilt | By default the bin takes the angle it stands at when it is switched on as "upright" (*Take upright again at every power-up* under **Tilt sensor (BMI160)**), so it never starts in tamper. That section also shows the saved upright pitch and roll and the board angle now. USE NOW takes the angle now; you can also type pitch and roll. Switch power-up off to keep one saved angle across restarts. Starting calibration also records upright. |
| Lid timing | *Lid stays open* (default 5 s) is how long the lid waits after motion stops. *Lid stays closed* (default 2 s) keeps a lid that just shut from opening again straight away, so it does not flap. |
| Temperature | Type a reference thermometer reading and press SET OFFSET (the BME680 reads about 2 C high from its own heat). |

Everything you save is kept in the ESP32's flash and survives a restart.

## 4. Firebase (cloud fallback for the app and the dashboard)

1. Create a Firebase project for Smart-Bin (a separate one from Garbage-Guard, so the free limits are not shared).
2. **Build > Realtime Database > Create database** (Singapore `asia-southeast1` is closest). Copy the URL, for example `https://smart-bin-g2-default-rtdb.asia-southeast1.firebasedatabase.app`.
3. **Rules** tab: paste `firmware/firebase.rules.json` and Publish.
4. **Build > Authentication > Sign-in method**: turn on **Email/Password**.
5. **Users** tab: add one user per bin whose email **starts with `bin`** (for example `bin01@smartbin.test`, `bin02@smartbin.test`), and one user per person (for example `operator@smartbin.test`, `collector1@smartbin.test`).
6. In the database, add `sb_users/<uid of each person>` = `true` (the uid is on the Users tab). Do not add the bins there.
7. **Project settings > General**: copy the **Web API key** (starts with `AIza`).
8. Put the URL, key and the **bin's** email and password in `secrets.h` and upload again. Or, in the dashboard's Cloud panel, type the bin account and press **SEND TO BINS**.
9. The Serial Monitor shows `[firebase] reachable` and the bin's Info screen shows *Firebase OK*.

What the bin writes, and how often (Integration Guide 6A):

| Path | When |
|---|---|
| `/sb/bins/<bin>/status` | At once on a status, lid, tamper, alarm or sensor change. Readings that moved (fill 1 %, weight 20 g, temperature 0.2 C, humidity 1 %, IAQ 5) every 5 s while someone watches, every 60 s otherwise. |
| `/sb/bins/<bin>/cal` | When the calibration changes |
| `/sb/commands/<bin>` | Checked every 2 s (10 s when nobody watches). Each command is deleted, then run. Older than 60 s is ignored. Only LOCK, UNLOCK, COLLECT. |
| `/sb/results/<push id>` | The answer to each command, for the confirm |
| `/sb/alerts/<bin>` | Each alert, trimmed to the newest 50 |
| `/sb/usage` | `bytes_month` and the meter fields, every 10 min |

## 5. ThingSpeak (history and charts)

1. Make one channel per bin with 8 fields: Fill %, Weight g, Temperature C, Humidity %, Air IAQ, Pressure hPa, Tilt deg, Flags.
2. Put each channel's **Write API Key** in `secrets.h` (`THINGSPEAK_WRITE_KEY_BIN_A` / `_B`). Keep `THINGSPEAK_SERVER` as `http://api.thingspeak.com`: with Firebase already holding an HTTPS session, a second one does not fit in memory. If you set it to `https://`, the bin closes the Firebase session before each ThingSpeak write, which works but reconnects to Firebase every time.
3. Optional, for LOCK/UNLOCK/COLLECT through ThingSpeak: **Apps > TalkBack > New TalkBack** for each bin, then put its ID and API key in `secrets.h`.
4. In the dashboard and the app, give each bin its channel ID, Read API key, TalkBack ID and TalkBack key in its SET sheet.

The bin writes every 30 s (sooner for a new alert, never faster than 15 s), skips writes when nothing moved, and always writes at least every 5 minutes. Field 8 is `motion + 2 × lid open + 4 × status`, the `status` text is the bin's IP. A sensor that is missing (no reading) leaves its field out of that write instead of sending `nan`.

## 6. The touchscreen

| Screen | When |
|---|---|
| A8 Boot | Power-on: sensor check list, IP, firmware |
| A1 Main | Normal; green, amber (50 %+) and the alarm boxes, timer rows for the lid and buzzer, "Full in ~" line |
| A2 Urgent | Fill at or over the fill limit |
| A3 PIN | COLLECT, or WI-FI SETUP on the Info screen; 4 digits, wrong PIN shakes and clears |
| A4 Collecting / A5 Done | Pickup running / finished, back to main after 30 s |
| A6 Tamper | 10 s countdown, clears if the bin settles, otherwise latched until the dashboard or app clears it |
| A7 Locked | Locked from the dashboard, app or auto-lock |
| A8 Calibrating | While the operator calibrates, with CANCEL |
| A9 Info | IP, Wi-Fi, signal, cloud, Bluetooth, firmware, uptime, theme |
| A10 Wi-Fi setup | Bluetooth on, pairing code, paired, connecting, connected or failed, Bluetooth off |
| Touch calibration | Four crosses, one at a time; saved on the bin when the last one is pressed |

**Touch calibration:** start it from the dashboard (Settings > Touchscreen > CALIBRATE TOUCH), from the app (command `TOUCH_CAL`), or on the bin by holding a finger anywhere on the screen for 5 seconds. Press each cross firmly, then let go. The result is kept on the bin (it survives uploads and restarts) and replaces the `TS_*` defaults in `config.h`. `TOUCH_CAL_RESET` goes back to those defaults. No press for 30 s cancels without changing anything.

The sun/moon key switches light and dark and is remembered. Info and PIN screens go back to the main screen after 30 s untouched.

**Wi-Fi setup over Bluetooth:** Info > WI-FI SETUP > Collect PIN. The bin advertises as `SmartBin-01`. When the dashboard or app connects, the bin shows a random 6-digit code; type it when the phone or computer asks. Three wrong codes stop pairing for 60 s. After the code matches, the link is encrypted, the dashboard sends the network name and password, and the bin shows its new IP. Bluetooth switches itself off after 5 minutes unused, or when the dashboard presses TURN BT OFF. The bin remembers laptops and phones it has paired with, so the next setup from the same device skips the 6-digit code (the Collect PIN is still needed on the bin). If a device was removed on one side only, remove SmartBin-01 from that device's Bluetooth settings and pair again. Collectors and operators can both do Wi-Fi setup. **The bin restarts once when Bluetooth setup starts and once when it ends.** Bluetooth keeps tens of KB of RAM, and the Firebase TLS connection needs that RAM, so normal boots give it back to the heap (`SmartBin/btmem.cpp`). `build_opt.h` also halves the web server's task stack for the same reason.

## 7. Local API

Everything in Integration Guide section 4 (`/api/status`, `/api/command`, `/api/calibrate`, `/api/simulate`) with the recommended replies (`409 {"ok":false,"why":"TAMPER active"}` for a refusal), plus:

| Call | What it does |
|---|---|
| `modes` in the status | `{"locked","collecting","calibrating","tamper"}`, each true or false. `status` is one word with a priority (calibrating, collecting, tamper, locked), so a lock under a tamper hides there; the dashboard and the app read these flags so both show the same keys. Also `pitch`/`roll` (board angle), `cal.tilt_ref_pitch`/`tilt_ref_roll`/`tilt_boot`/`lid_close_s`, and `sim` (the value each simulated sensor is held at). |
| `GET /api/events` | Server-Sent Events: a `status` event on every change (at most 4 a second), a `ping` every 10 s. The dashboard uses it; `/api/status` stays for the app. |
| `POST /api/cloud` | `{"url","key","email","pass"}` stores the Firebase account on the bin (the dashboard's SEND TO BINS) |
| `POST /api/command {"cmd":"BUZZ_TEST","sound":"full"}` | Plays any tune by name (`full`, `tamper`, `lock`, `bt_paired`, ...) |
| `POST /api/command {"cmd":"TOUCH_CAL"}` | Shows the touch calibration screen on the bin |
| `POST /api/command {"cmd":"TOUCH_CAL_RESET"}` | Clears the saved touch calibration |
| `GET /` | The web dashboard |

Extra status fields: `faults` (sensors not answering, whose readings are left out rather than sent as 0), `tamper_phase` (`COUNTDOWN` or `LATCHED`), `last_cmd`, `uptime_s`, `rssi`, `fw`, `name`, `gas_k` (raw gas resistance in kOhm), and `touch` (`swap`, `x0`, `x1`, `y0`, `y1`, `custom`: true once calibrated on the bin).

The buzzer settings `buzz_on`, `buzz_vol` (1 to 3), `buzz_ui`, `buzz_lid`, `buzz_repeat_s`, `quiet_on`, `quiet_from`, `quiet_to` (minutes after midnight, Manila time) are part of `cal`.

## 8. How it is built

`loop()` does nothing. Five FreeRTOS tasks wait for interrupts, queues or their own timers:

| Task | Core | Job |
|---|---|---|
| control | 1 | PIR interrupt, commands, lid timer, lock, tamper, auto-lock, alarms, RGB LED. The only task that moves the servo. |
| sensor | 1 | HC-SR04 (median of 5), HX711, BME680 (start, then collect later, never blocking), BMI160. The only task on I2C. |
| ui | 1 | Touch interrupt and the screen. The only task on SPI. Redraws only what changed. |
| cloud | 0 | Firebase and ThingSpeak, so a slow HTTPS call never freezes the lid or the screen. Backs off 5, 15, 30, 60 s when the internet is down. |
| push | 0 | Sends `/api/events` to open dashboards |

Readings live in one shared state behind a mutex; nobody holds it across I2C, SPI or HTTP.

## 9. Things to know

- **Air quality (IAQ).** This uses its own estimate from the BME680 gas resistance and humidity (a warm-up of 5 minutes on first use, 2 minutes after that, and a baseline saved every hour), not Bosch's BSEC library. `iaq_acc` goes 0 (warming up, the screen shows `--`) to 3 over about 30 minutes. Say "estimated IAQ" in the paper.
- **HTTPS certificates are not checked** (`setInsecure()`), which is common on student ESP32 projects but means the bin trusts any server. Adding the Google and ThingSpeak root certificates fixes that.
- **GPIO 12 (buzzer)** is a boot strapping pin. Our buzzer module is transistor-driven, so no pull-up or pull-down is needed. If the board ever refuses to boot with the buzzer plugged in, that pin is the first suspect.
- **BMI160 INT1 to GPIO 34** is optional. Without the wire the tilt is read every 200 ms, which is fast enough.
- `FB_REQUIRE_WATCHERS` in `config.h` is 0, so the bin keeps the fast cloud pace even when only the app watches (the app v1.2 does not write `/sb/watchers` yet). Set it to 1 once it does, to save more of the free allowance.
