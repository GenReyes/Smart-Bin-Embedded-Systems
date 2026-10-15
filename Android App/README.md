# Smart-Bin Android app

The phone app for the Smart-Bin system, for both the operator and the collector. It follows `Mockups/smartbin-app-mockup v2.html` screen for screen and borrows the feel of the Garbage-Guard app: a light and a dark logo on the home screen, its own sound for each kind of action, and a vibration pattern for each kind of alert.

It runs with no hardware. Bins without an address show demo data that fills and drifts the way real bins do, so every screen can be shown in class. Give a bin an IP address and it switches to live readings over Wi-Fi.

Version 1.1. Kotlin and Jetpack Compose, the same stack as Garbage-Guard (AGP 9.4.1, Gradle 9.6.0, compile and target SDK 37, minimum Android 8.0).

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

A ready-built debug APK is at `release/Smart-Bin-v1.apk` for installing on a phone without building.

## What is in it

**First launch.** Pick Operator or Collector, then the app asks once for permission to send alerts. The role chip in the header switches roles later.

**Header.** The SMART-BIN brand (tap it for the logo chime), the mode chip (NORMAL, SIMULATION, CALIBRATING, SIM + CAL, ALERT) and the sun and moon theme key. Under it: a pill with unread or active alerts that opens the Needs attention sheet, the link chip (Local Wi-Fi, Cloud, Not connected, Demo data) that opens Connection, and the role chip. The accent colour of the whole app follows the mode, as on the dashboard.

**Overview.** With one or two bins, a full card per bin: the bin icon with a moving fill wave, fill percent, six status chips, the fill bar with its limit mark, four sensor tiles that turn red at their limit, and LOCK or UNLOCK, RESET TAMPER (operator) and COLLECT or DONE. With three or more, a grid of tiles with filters (All, Needs action, Sim / Cal) and a legend. Collect next lists the fullest bins first with a time to full. Operators can add bins (1 to 3 at a time) and edit a bin's name, place, IP address and cloud name with SET.

**Charts.** Fill level over 15 minutes, 1 hour, 6 hours or 24 hours, for one bin or the five fullest, with the alert line. Environment shows temperature, humidity and air quality as a share of their limits, or one reading in real units (temperature 0 to 85 C, humidity 0 to 100 %, air quality 0 to 500 IAQ, pressure 300 to 1100 hPa). The expand key turns the phone sideways for a full-width chart.

**Settings.** The calibration switch (one bin at a time, Wi-Fi only), then fold-out groups: fill range with USE NOW, alert limits, the BME680 offsets and gas baseline, lid and lock, and weight with tare and calibrate. Changes wait in a save bar until SAVE. Collectors see the collect PIN and read-only values. The App section has Connection, Bluetooth setup, Reload from bin, Export bin list, role, appearance (Auto, Light, Dark) and push alerts.

**Sound and feel** (in Settings). Button sounds on or off, vibration on or off, vibration strength (Light, Normal, Strong), keep the screen on, a preview of each alert's sound and buzz, and a shortcut to Android's alert settings.

**Simulation** (operator). One row per sensor with AUTO or MANUAL: fill and air quality sliders, weight, temperature and humidity steppers, tamper and motion switches, and the lid. APPLY sends it to the bin; ALL TO AUTO ends it.

**Alerts.** Filter by kind (all, full, sensor, tamper, lock, pickup, setup) and by date (today, 7 days, 30 days, any). MARK READ and DELETE act on exactly what is shown, delete asks first, the dot marks one read or unread, the cross removes one, and Show older loads six more at a time.

**Connection.** The two routes with the one in use, each bin's address and state, the Firebase account form with TEST, TEST AND SAVE and RESTORE WORKING, and the data used this month.

**Bluetooth setup.** The four steps from the mockup: pair, pick a network, send, Bluetooth off. It is a walkthrough with a stand-in for the bin until the firmware has Bluetooth provisioning; USE FOR stores the address it shows.

## Garbage-Guard features carried over

- **Light and dark home-screen logo.** The app has two launcher entries, one per logo, and switches them as it leaves the screen to match the theme it was left in. Some launchers take a few seconds to redraw the icon.
- **Sounds.** 34 short sounds made by `tools/make_sounds.py`: taps, choices, switches, steppers, tabs, open and back, confirm and danger, theme changes, the boot chime, saved, lock and unlock, pickup and done, calibration, simulation, the link going up, down or to the cloud, a bin going quiet or coming back, and one per kind of alert. Rising tones mean on, forward or OK; falling tones mean off, back or no. Run `python tools/make_sounds.py` to make them again after changing the table.
- **Vibration.** Plain keys use Android's own touch clicks. Events and alerts each have their own rhythm, so a full bin, a tamper and a lock feel different in a pocket. The strength setting scales them all.
- **Notifications.** One Android channel per kind of alert (full, tamper, sensor, lock, pickup, setup), each with its own sound and vibration, so each can be changed or silenced in Android settings. A full-bin alert has a Start pickup button that works without opening the app. While push alerts are on, a quiet foreground service keeps reading the bins after the app is closed.
- **Keep screen on**, and the logo tap chime.

## Connecting to the bins

The app reads every bin once a second over the local Wi-Fi first. When a bin stops answering it tries again after 2 s, and after three misses every 30 s. If a Firebase account is saved, it reads that bin from the cloud every 5 s in the meantime. A bin with no reading for 90 s shows as not answering. A change of network makes it try Wi-Fi again at once.

Over the cloud only LOCK, UNLOCK and COLLECT work. Calibration and simulation need the bins' Wi-Fi.

### Local API the firmware serves

| Call | Body | What it does |
|---|---|---|
| `GET /api/status` | | Readings, status, `cal` object and `sim_mask` |
| `POST /api/command` | `{"cmd": "LOCK"}` | LOCK, UNLOCK, CLEAR_TAMPER, COLLECT, COLLECT_DONE, CALIBRATE_START, CALIBRATE_END, RESET_GAS, TARE, CAL_WEIGHT (`"grams"`) |
| `POST /api/calibrate` | calibration values | Stores them in EEPROM |
| `POST /api/simulate` | `{"mask": 5, "values": {...}}` | Holds the chosen sensors at the given values |

Status fields the app reads: `fill`, `distance_cm`, `weight_g`, `temp`, `temp_raw`, `humidity`, `pressure`, `iaq`, `iaq_acc`, `tilt`, `motion`, `lid` (`OPEN` or `CLOSED`), `status` (`OK`, `LOCKED`, `TAMPER`, `COLLECTING`, `CALIBRATING`), `cal`, `sim_mask`. The dashboard's older names (`fill_percentage`, `weight_grams` and so on) also work. Simulation mask bits: 0 fill, 1 weight, 2 air quality, 3 temperature and humidity, 4 tamper, 5 motion, 6 lid.

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

## Testing without the bins

`tools/smartbin_mock_server.py` plays the bins on a computer. It needs Python 3.9 or newer and no packages.

```
python tools/smartbin_mock_server.py              two bins on ports 8081 and 8082
python tools/smartbin_mock_server.py --bins 3     three bins
python tools/smartbin_mock_server.py --cloud 8090 also a fake Firebase on port 8090
```

Put the phone and the computer on the same Wi-Fi. In the app, open a bin with SET and type the address the script prints, such as `192.168.1.20:8081`. The link chip turns to Local Wi-Fi and the commands show up in the script's window.

For cloud mode, enter `http://<computer address>:8090` as the database URL on the Connection page, any API key, any email and a password of six or more characters, then TEST AND SAVE. Restart the script with `--cloud 8090 --no-local` and the app moves to Cloud after the bins stop answering.

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
