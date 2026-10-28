# Smart-Bin: what changed, and where to start on Firebase and ThingSpeak

From: Brian. Updated 28 Oct 2026. Base: `main` at the commit that added this file (the only branch now).

This picks up from `Android App/INTEGRATION.md` (17 Oct). It lists what changed since then and what is already written for Firebase and ThingSpeak, so the cloud work starts from the current code instead of from scratch.

## 1. Short version

- The app is **v3.0** (`Android App/Smart-Bin v3.0.apk`). Old v2.x APKs are gone. The dashboard stays v2.
- The Firebase and ThingSpeak code is **already written on both sides**: firmware (`firmware/SmartBin/cloud.cpp`), app (`data/Cloud.kt`, `data/ThingSpeak.kt`, `data/Hub.kt`) and dashboard. What is missing is the **accounts, keys and a real test**. No Firebase project or ThingSpeak channel exists yet.
- Steps 4, 5 and 6 of the old integration guide (command results, watchers, cloud alerts) are **done in the app**, so `FB_REQUIRE_WATCHERS` in `firmware/SmartBin/config.h` can be set to `1` once Firebase is up.
- Keys never go into Git: the firmware reads `secrets.h` (git-ignored, copy from `secrets.example.h`), the app reads `local.properties` (git-ignored) or the Connection page.

## 2. What changed since 17 Oct

### Firmware (`firmware/SmartBin/`)

| Change | Files | Why it matters for the cloud |
|---|---|---|
| `modes` object in the status: `{"locked","collecting","calibrating","tamper"}` | `state.cpp/.h`, `net.cpp` | `status` is one word, so a lock under a tamper was hidden. The app and dashboard read `modes` now. The Firebase status write sends it too, and a change in `modes` counts as an "important change" (written at once). |
| `pitch`/`roll` (board angle now) in the status | `sensors.cpp`, `state.*` | Shown in the app's Tilt sensor group |
| Tilt upright taken at every power-up (`cal.tilt_boot`, on by default), editable `tilt_ref_pitch`/`tilt_ref_roll` | `sensors.cpp`, `net.cpp` (`/api/calibrate`) | `sb/bins/<bin>/cal` on Firebase now also carries `tilt_ref_pitch` and `tilt_ref_roll` |
| `cal.lid_close_s` (default 2 s): a lid that just closed stays shut | `control.cpp`, `state.h` | New calibration key; `Cal` struct grew, older saved blocks still load |
| `sim` values in the status (what each simulated sensor is held at) | `state.cpp` | App and dashboard show the same simulation |
| HX711 junk/frozen reading check, pull-up on DT | `sensors.cpp` | An unplugged scale shows in `faults` as `HX711` |
| CI firmware build removed | `.github/workflows` | Firmware is compiled and uploaded from the laptop (Arduino IDE) |

### App (`Android App/`, v3.0)

| Change | Files |
|---|---|
| Reads `modes`, `pitch`, `roll`, `sim` from the status; lock/pickup/tamper alerts follow the `modes` flags, the same rules as the dashboard | `data/LocalApi.kt`, `data/Models.kt`, `data/Hub.kt` |
| Calibration mode follows the bin's report: started from the dashboard or the touchscreen, the app shows it too (4 s grace after the app switches it) | `data/Hub.kt` (`syncCalib`), `ui/AppViewModel.kt` |
| Settings: new **Tilt sensor (BMI160)** group (USE NOW, pitch/roll, power-up switch) and **Lid stays closed** | `ui/Settings.kt` |
| Fix: v3.0 crashed on launch (a collector started before the properties it reads existed). Fixed and checked on the emulator | `ui/AppViewModel.kt` |
| Mock server knows the new fields | `tools/smartbin_mock_server.py` |
| The APK is built on the laptop; the CI APK build only runs by hand | `.github/workflows/android-apk.yml` |

### Dashboard (`Web Dashboard/smartbin-dashboard.html`)

Same changes as the app (modes, Tilt sensor group, Lid stays closed). After editing it, run `python firmware/tools/embed_dashboard.py` so `firmware/SmartBin/dashboard_gz.h` (the copy the bin serves) matches.

### Screenshots

`Documents/Figures/App Screens/v3.0/` has the 11 Settings screens of v3.0 from the emulator.

## 3. What already exists for Firebase

No SDK anywhere: everything is the Realtime Database REST API with email/password sign-in, so the URL, key and account can be typed in at run time.

| Path | Writer | Reader | Code |
|---|---|---|---|
| `sb/bins/<bin>/status` | bin: `/api/status` JSON plus `ts` and `local_ip`, no `cal`/`touch` | app every 5 s, dashboard | `cloud.cpp` `cloudTask`; `Cloud.status` |
| `sb/bins/<bin>/cal` | bin, when the calibration changes | dashboard (the app does **not** read it yet, see section 5) | `cloud.cpp` |
| `sb/commands/<bin>/<push key>` | app/dashboard `{"cmd","ts","from"}`; LOCK, UNLOCK, COLLECT only | bin every 2 s (10 s idle), deletes then runs; over 60 s old is refused | `pollCommands`; `Cloud.command` |
| `sb/results/<push key>` | bin `{"ok","cmd","why","ts"}` | app polls 1 s for 8 s, then deletes | `flushResults`; `Hub.confirm` |
| `sb/watchers/<id>` | app every 10 s while in front and reading over Firebase; deleted in background | bin every 30 s | `readWatchers`; `Hub.watchers` |
| `sb/alerts/<bin>` | bin, newest 50 kept | app once after start (alerts it missed) | `flushAlerts`, `trimAlerts`; `Hub.cloudAlerts` |
| `sb/usage/...` | bin, every 10 min (`by_bin/<bin>`, `bytes_month`, `fraction`, `level`) | app data meter, dashboard | `writeUsage`; `Cloud.usageBytes` |
| `sb_users/<uid>` = `true` | the team, by hand | app/dashboard TEST | `Cloud.test` |

Rules: `firmware/firebase.rules.json`. Bins are accounts whose email starts with `bin`; people must be in `sb_users`.

Write pace on the bin (`config.h`): status at once on an important change, otherwise every 5 s while watched and 60 s when nobody watches (slower again near the 10 GB/month limit).

### Steps to finish Firebase

1. Make the project, database (`asia-southeast1`), rules, Email/Password sign-in, users and `sb_users`: `firmware/README.md` section 4 has every click.
2. Firmware: copy `secrets.example.h` to `secrets.h`, fill `FIREBASE_URL`, `FIREBASE_API_KEY`, and the **bin's** email and password. Upload. Serial shows `[firebase] reachable`; the bin's Info screen shows Firebase OK. (Or send the account from the dashboard's Cloud panel, SEND TO BINS.)
3. App: put a **person's** account in `Android App/local.properties` (`sb.fb.url`, `sb.fb.key`, `sb.fb.email`, `sb.fb.password`) and rebuild, or type it on the Connection page and press TEST AND SAVE.
4. In `config.h` set `FB_REQUIRE_WATCHERS 1`, so the bins slow down when nobody is watching.
5. Test (section 6).

## 4. What already exists for ThingSpeak

| What | Detail | Code |
|---|---|---|
| Bin writes its channel | `/update` every 30 s when something moved, sooner for a new alert (never under 15.5 s), at least every 5 min | `cloud.cpp` `tsWrite` |
| Fields | 1 fill %, 2 weight g, 3 temp C, 4 humidity %, 5 IAQ, 6 pressure hPa, 7 tilt deg, 8 `motion + 2*lid + 4*status`; `status` text = bin IP | same in `ThingSpeak.kt` |
| Status numbers in field 8 | 0 OK, 1 LOCKED, 2 TAMPER, 3 COLLECTING, 4 CALIBRATING | |
| App reads | `feeds/last.json` every 15 s with the read key, when Wi-Fi and Firebase do not answer | `ThingSpeak.last`, `Hub.kt` |
| Commands | app adds LOCK/UNLOCK/COLLECT to the bin's TalkBack; bin runs `commands/execute.json` every 5 s (15 s idle) | `ThingSpeak.command`; `talkBack` |
| Message meter | the app estimates the yearly count from entry numbers (3 million/year free) | `Hub.noteEntry` |

### Steps to finish ThingSpeak

1. One channel per bin, 8 fields in the order above (`firmware/README.md` section 5).
2. Firmware `secrets.h`: `THINGSPEAK_WRITE_KEY_BIN_A`/`_B`, and for commands `THINGSPEAK_TALKBACK_ID_BIN_A`/`_KEY_BIN_A` (and `_B`).
3. App and dashboard: in each bin's SET sheet, channel ID, read key, TalkBack ID and TalkBack key.
4. Test (section 6).

## 5. Gaps worth knowing before you start

- **The app never reads `sb/bins/<bin>/cal`.** Over Firebase it keeps the last calibration it got over Wi-Fi. Fine for now (calibration only works over Wi-Fi), but thresholds for alerts use that copy.
- **ThingSpeak field 8 has one status number**, not `modes`. A lock under a tamper shows as TAMPER only, and the app falls back to `status` for its flags. Adding the mode bits would need a field change on both sides (`tsWrite` and `ThingSpeak.last`).
- **Only LOCK, UNLOCK and COLLECT go over the cloud.** Calibration, CLEAR_TAMPER, simulation and touch calibration need the bin's Wi-Fi, on purpose.
- **TLS is not verified on the bin** (`tls.setInsecure()` in `cloudTask`). It works, but a pinned root certificate would be better for the final build.
- **The Firebase route in the app ignores data older than 90 s** (`Hub.kt`), so a bin that stops writing shows "not answering" instead of old readings.
- The app's own `Android App/README.md` Firebase table only lists 4 paths; section 3 above is the full list.

## 6. Testing

Without the bins, the mock server plays Firebase and ThingSpeak:

```
python "Android App/tools/smartbin_mock_server.py" --cloud 8090 --ts 8095
```

App: Connection page, database URL `http://<computer IP>:8090`, any key, any email, a password of 6+ characters, TEST AND SAVE. ThingSpeak server `http://<computer IP>:8095`; bin 1 uses channel `1001` and TalkBack `2001`. Restart with `--no-local` to see the app fall back to the cloud.

With the real bins:

| # | Test | Pass |
|---|---|---|
| 1 | Bin on Wi-Fi with Firebase set | Serial `[firebase] reachable`, `sb/bins/bin01/status` updates in the console |
| 2 | Phone on mobile data, app open | Card shows the Cloud chip with live readings |
| 3 | LOCK from the app on mobile data | "Confirmed" within 8 s, bin shows the locked screen |
| 4 | App to the background for 1 min | `sb/watchers/<id>` is gone, the bin writes every 60 s (with `FB_REQUIRE_WATCHERS 1`) |
| 5 | Tilt the bin while the app is closed, then open it | The tamper alert from `sb/alerts` appears |
| 6 | ThingSpeak keys set, Firebase account removed from the app | Readings from ThingSpeak within 30 s |
| 7 | LOCK through TalkBack | Bin locks within 15 s |

Questions about the app side: ask Brian. Firmware details are in `firmware/README.md`; the dashboard has a working version of every cloud step.
