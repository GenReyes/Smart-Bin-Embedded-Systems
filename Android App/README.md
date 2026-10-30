# Smart-Bin Android app

The phone app for the Smart-Bin system, for both the operator and the collector. It borrows the feel of the Garbage-Guard app: a light and a dark logo on the home screen, its own sound for each kind of action, and a vibration pattern for each kind of alert.

It runs with no hardware. Turn DEMO on at the start page and three made-up bins appear next to the real ones. Left alone, they walk through every outcome by themselves (full, tamper, bad air, a sensor that stops answering, links coming and going, a pickup) so every screen can be shown in class. Pair a real bin over Bluetooth and it switches to live readings over Wi-Fi.

**Connecting to the real bins:** the app covers everything the firmware offers: Bluetooth Wi-Fi setup, the status fields (including `modes`, `faults`, tilt and simulation values), touch calibration, Firebase command results and watchers, and ThingSpeak with TalkBack. The API and cloud paths are listed in `firmware/README.md`.

Version 3.1. Kotlin and Jetpack Compose, the same stack as Garbage-Guard (AGP 9.4.1, Gradle 9.6.0, compile and target SDK 37, minimum Android 8.0).

## Build and run

This folder is a complete Android Studio project inside the repository. No separate project is needed.

1. In Android Studio choose **File > Open** and pick the `Android App` folder (the one with `settings.gradle.kts`).
2. Let the Gradle sync finish. Android Studio writes `local.properties` with your SDK path.
3. Plug in a phone with USB debugging on, or start an emulator, and press **Run**.

From a terminal in this folder:

```
gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to a phone to install it there.

A ready-built debug APK, `Smart-Bin v3.1.apk`, sits in this folder for installing on a phone without building.

## What is in it

**Start page.** Every time the app opens it starts here: the animated logo, Operator or Collector, and the DEMO switch under them. Demo mode is turned on or off only here, and the DEMO badge in the header (or Start page in App settings) comes back to it. The first time, the app asks once for permission to send alerts after a role is picked. No alert sound or notification plays while the start page is showing.

**Operator PIN.** Operator asks for a 4 to 8 digit PIN; the first time, the PIN is made there (typed twice). Only a salted PBKDF2 hash is kept. After 5 wrong PINs the app waits 30 s, doubling with each further miss, and operator drops back to collector after 2 min in the background. An operator can change the PIN in App settings after typing the current one. The dashboard uses the same rules. A forgotten PIN is reset by clearing the app's data.

**Logo.** A bin with a rising fill wave, a controller chip on its front, a status light on the lid and a Wi-Fi signal above it, in the light and dark colours. It plays a short build-up (lid, fill, chip, signal) on the start page and when tapped, then holds its still frame, which is also the home-screen icon. The dashboard draws the same logo.

**Header.** The SMART-BIN brand (tap it for the logo chime), the mode chip (NORMAL, SIMULATION, CALIBRATING, SIM + CAL, ALERT) the sun and moon theme key, and the gear key for App settings. Under it: a pill with unread or active alerts that opens the Needs attention sheet, the link chip that opens Connection, and the role chip. The accent colour of the whole app follows the mode, as on the dashboard.

**Link chip.** It shows every route that answers, each in its own colour and icon: Wi-Fi green, Firebase orange, ThingSpeak blue. With one route it names it; with two it shows both names, such as Wi-Fi + Firebase; with all three it says All 3 links. A ring in the colour of the route the readings come from and a strip of all the colours underneath make each mix look different.

**Overview.** With one or two bins, a full card per bin: the bin icon with a moving fill wave, fill percent, six status chips, the fill bar with its limit mark, a line of the last hour's fill with how long ago the last reading came (red once the bin stops answering), four sensor tiles that turn red at their limit (a sensor that stops answering shows -- and Not answering; the air tile counts down while the BME680 warms up), and LOCK or UNLOCK, RESET TAMPER (operator) and COLLECT or DONE. With three or more, a grid of tiles with filters (All, Needs action, Sim / Cal) and a legend. Collection priority sorts by urgency, the same as the dashboard: full bins first (NOW), then by time to full (SOON, TODAY, LATER), and bins that are not filling last (IDLE). Tap a row to open that bin; in the bin's sheet, swipe left or right to move between bins. Operators can add bins (1 to 3 at a time) and edit a bin's name, place, IP address and cloud name with SET, including its Firebase cloud name and its ThingSpeak channel, read key and TalkBack ID and key.

**Charts.** Fill level over 15 minutes, 1 hour, 6 hours or 24 hours, for one bin or the five fullest, with the alert line. Environment shows temperature, humidity and air quality as a share of their limits, or one reading in real units (temperature 0 to 85 C, humidity 0 to 100 %, air quality 0 to 500 IAQ, pressure 300 to 1100 hPa). The expand key turns the phone sideways for a full-width chart.

**Settings.** The calibration switch (one bin at a time, Wi-Fi only), then fold-out groups: fill range with USE NOW, alert limits, the BME680 offsets and gas baseline, lid and lock, weight with tare and calibrate, and for operators the touchscreen (CALIBRATE TOUCH and RESET). Every group starts folded, with its summary on the right. SHOW ALL opens every group at once and HIDE ALL folds them again. Changes wait in a save bar until SAVE. The Collect PIN sits at the top for both roles; collectors see read-only values. Under it are Reload from bin and a row to App settings. Everything about the app itself is behind the gear.

**App settings** (the gear at the top right). Two big keys for Sound and Vibration, vibration strength (Light, Normal, Strong), a sound test with every sound in the same groups, icons and colours as the dashboard's (each plays with its vibration even with sound off), and keep the screen on. Then appearance (Auto, Light, Dark) and role, Connection and Bluetooth setup, push alerts, Unrestricted battery (asks Android to stop pausing the app in the background), Android alert settings, Start page, Export bin list, and the demo options while demo is on.

**Demo options** (in App settings, only while demo is on). Any choice here pauses the automatic tour for 60 s. They act on the demo bins only: fill speed (Pause, Real, Normal, Fast), pretend links (Wi-Fi, Firebase, ThingSpeak in any mix, to show the link colours), and one-tap events on a bin: FULL, TAMPER, BAD AIR, EMPTY, and GO QUIET, which makes the bin stop answering until ANSWER AGAIN. RESET puts it all back. They are forgotten when the app closes, and while none is used the demo runs as normal.

**Simulation** (operator). One row per sensor with AUTO or MANUAL: fill and air quality sliders, weight, temperature and humidity steppers, tamper and motion switches, and the lid. APPLY sends it to the bin; ALL TO AUTO ends it.

**Alerts.** Filter by kind (all, full, sensor, tamper, lock, pickup, setup) and by date (today, 7 days, 30 days, any). MARK READ and DELETE act on exactly what is shown, delete asks first, the dot marks one read or unread, the cross removes one, and Show older loads six more at a time.

**Connection.** The three routes, each marked IN USE, ANSWERS, NO ANSWER or NOT SET UP, each bin's address and state, the firmware version, signal and uptime of each real bin, the Firebase account form with TEST, TEST AND SAVE, RESTORE WORKING and SEND TO BINS (operator, gives every bin on Wi-Fi the account), the ThingSpeak server with TEST AND SAVE, and the data used this month.

**Bluetooth setup.** Real BLE provisioning against the bin's setup service. On the bin, tap i, WI-FI SETUP and the Collect PIN. In the app, PAIR A BIN looks for bins nearby and lists them; pick one and type the 6-digit code its screen shows into Android's pairing box (new each time, 60 s, 3 wrong codes stop pairing for 60 s). The app then reads the networks the bin sees, sends the name and password over the encrypted link, and waits for CONNECTED or FAILED with its reason. USE FOR stores the address on the picked bin, or ADD AS BIN adds a new one, and TURN BT OFF switches the bin's Bluetooth off. If the phone remembers an old pairing, FORGET AND PAIR AGAIN clears it. A demo bin uses a stand-in code screen; a network name with "fail" in it shows a failed join.

## Garbage-Guard features carried over

- **Light and dark home-screen logo.** The app has two launcher entries, one per logo, and switches them as it leaves the screen to match the theme it was left in. Some launchers take a few seconds to redraw the icon.
- **Sounds.** 46 short sounds made by `tools/make_sounds.py`: taps, choices, switches, steppers, tabs, open and back, confirm and danger, theme changes, the boot chime, saved, lock and unlock, pickup and done, calibration, simulation, the link going up, down or to the cloud, a bin going quiet or coming back, the PIN keypad (digit, wrong PIN, locked out, operator in, back to collector, PIN changed), a bin confirming a command or not, Bluetooth pairing (paired, wrong code, pairing stopped) and the bin joining Wi-Fi, and one per kind of alert. No two moments share a sound or a vibration. Rising tones mean on, forward or OK; falling tones mean off, back or no. Run `python tools/make_sounds.py` to make them again after changing the table.
- **Vibration.** Plain keys use Android's own touch clicks. Events and alerts each have their own rhythm, so a full bin, a tamper and a lock feel different in a pocket. The strength setting scales them all.
- **Notifications.** One Android channel per kind of alert (full, tamper, sensor, lock, pickup, setup), each with its own sound and vibration, so each can be changed or silenced in Android settings. A full-bin alert has a Start pickup button that works without opening the app. While push alerts are on, a quiet foreground service keeps reading the bins every 5 s while the app is in the background, so alerts arrive there. Alerts are posted only after you are past the start page and the role picker. Swiping the app away from recent apps, or backing out of it, stops the service and the alerts. Unrestricted battery in App settings keeps Android from pausing it.
- **Keep screen on**, and the logo tap chime.

## Connecting to the bins

The app reads every bin once a second over the local Wi-Fi first. When a bin stops answering it tries again after 2 s, and after three misses every 30 s. If a Firebase account is saved, it reads that bin from Firebase every 5 s in the meantime, and if the bin has a ThingSpeak channel and Firebase gives nothing, from ThingSpeak every 15 s. A bin with no reading for 90 s shows as not answering. A change of network makes it try Wi-Fi again at once. Every 30 s the routes not in use get one small call, so the link chip knows which of them answer.

Over Firebase and ThingSpeak only LOCK, UNLOCK and COLLECT work. Calibration and simulation need the bins' Wi-Fi.

### Local API the firmware serves

| Call | Body | What it does |
|---|---|---|
| `GET /api/status` | | Readings, status, `modes` (locked, collecting, calibrating, tamper flags), `pitch`/`roll`, `cal` object (incl. `lid_close_s`, `tilt_boot`, `tilt_ref_pitch`, `tilt_ref_roll`), `sim_mask` and `sim` values |
| `POST /api/command` | `{"cmd": "LOCK"}` | LOCK, UNLOCK, CLEAR_TAMPER, COLLECT, COLLECT_DONE, CALIBRATE_START, CALIBRATE_END, RESET_GAS, TARE, CAL_WEIGHT (`"grams"`), TOUCH_CAL, TOUCH_CAL_RESET, BUZZ_TEST (`"sound"`) |
| `POST /api/calibrate` | calibration values | Stores them in EEPROM |
| `POST /api/simulate` | `{"mask": 5, "values": {...}}` | Holds the chosen sensors at the given values |

Status fields the app reads: `fill`, `distance_cm`, `weight_g`, `temp`, `temp_raw`, `humidity`, `pressure`, `iaq`, `iaq_acc`, `tilt`, `motion`, `lid` (`OPEN` or `CLOSED`), `status` (`OK`, `LOCKED`, `TAMPER`, `COLLECTING`, `CALIBRATING`), `cal`, `sim_mask`, and `touch` (`swap`, `x0`, `x1`, `y0`, `y1`, `custom`). The dashboard's older names (`fill_percentage`, `weight_grams` and so on) also work. Simulation mask bits: 0 fill, 1 weight, 2 air quality, 3 temperature and humidity, 4 tamper, 5 motion, 6 lid.

### Firebase layout

This is the layout the app expects. The firmware has to write the same paths.

| Path | Who writes | Contents |
|---|---|---|
| `/sb/bins/<cloud name>/status` | the bin | The same JSON as `/api/status`, plus `ts` (ms) and `local_ip` |
| `/sb/commands/<cloud name>` | the app | `{"cmd": "LOCK", "ts": ..., "from": "app"}`, LOCK, UNLOCK or COLLECT only |
| `/sb/usage/bytes_month` | the bins | Bytes sent this month, for the data meter |
| `/sb_users/<uid>` | the team | Present for each account allowed to use the app |

The cloud name of a bin defaults to `bin01`, `bin02` and so on, and can be changed in its SET sheet. Sign-in uses Firebase Authentication with email and password. The password is stored on the phone encrypted with the Android Keystore.

To build the team's account into the app, add these lines to `local.properties` (it is git-ignored, so they never reach GitHub):

```
sb.fb.url=https://<project>-default-rtdb.asia-southeast1.firebasedatabase.app
sb.fb.key=AIza...
sb.fb.email=collector1@example.com
sb.fb.password=...
```

The account can also be typed in on the Connection page.

### ThingSpeak layout

Each bin has its own channel. The firmware writes it with the channel's write API key, at most every 15 s (the free licence's limit, and 3 million messages a year across all channels). The app reads the newest entry with the read API key.

| Field | Contents |
|---|---|
| field1 | Fill % |
| field2 | Weight g |
| field3 | Temperature C |
| field4 | Humidity % |
| field5 | Air quality (IAQ) |
| field6 | Pressure hPa |
| field7 | Tilt deg |
| field8 | motion + 2 × lid open + 4 × status (0 OK, 1 LOCKED, 2 TAMPER, 3 COLLECTING, 4 CALIBRATING) |
| status | The bin's local IP address |

Commands go through the bin's TalkBack queue: the app adds `LOCK`, `UNLOCK` or `COLLECT`, and the bin takes the next command each time it polls. In the app, put the channel ID, read API key, TalkBack ID and TalkBack API key in the bin's SET sheet. The server is `https://api.thingspeak.com` unless you change it on the Connection page.

ThingSpeak has no call that reports how many messages an account has used; the count is on the account page of its website. So the Connection page estimates it: it watches how fast each channel's entry numbers rise and projects the year against the 3 million limit. One bin writing every 20 s uses about 1.58 million a year, so two bins should write every 30 s or slower.

## Testing without the bins

`tools/smartbin_mock_server.py` plays the bins on a computer. It needs Python 3.9 or newer and no packages.

```
python tools/smartbin_mock_server.py              two bins on ports 8081 and 8082
python tools/smartbin_mock_server.py --bins 3     three bins
python tools/smartbin_mock_server.py --cloud 8090 also a fake Firebase on port 8090
python tools/smartbin_mock_server.py --ts 8095    also a fake ThingSpeak on port 8095
```

Put the phone and the computer on the same Wi-Fi. In the app, open a bin with SET and type the address the script prints, such as `192.168.1.20:8081`. The link chip turns to Local Wi-Fi and the commands show up in the script's window.

For cloud mode, enter `http://<computer address>:8090` as the database URL on the Connection page, any API key, any email and a password of six or more characters, then TEST AND SAVE. Restart the script with `--cloud 8090 --no-local` and the app moves to Cloud after the bins stop answering.

For ThingSpeak, enter `http://<computer address>:8095` as the server on the Connection page and press TEST AND SAVE. In bin 1's SET sheet use channel `1001` and TalkBack `2001` (bin 2 is 1002 and 2002), with any keys.

## Folder layout

| Path | Contents |
|---|---|
| `app/src/main/java/smartbin/data/` | Models, the Wi-Fi and Firebase clients, saved settings, demo data, and `Hub`, which reads the bins and raises alerts |
| `app/src/main/java/smartbin/notify/` | Sounds, vibration patterns, notification channels, the Start pickup action, the background service |
| `app/src/main/java/smartbin/ui/` | Theme tokens and shadows from the mockup, icons, shared controls, and one file per group of screens |
| `app/src/main/res/` | Launcher logos (light and dark), fonts, sounds |
| `tools/` | The sound maker and the mock bin server |

The code sits in the short package `smartbin` (application ID `edu.tup.smartbin`) to keep folder paths short.

## Credits

Inter typeface by Rasmus Andersson, SIL Open Font License 1.1 (`licenses/Inter-OFL.txt`). Icons are the dashboard's own line icons.
