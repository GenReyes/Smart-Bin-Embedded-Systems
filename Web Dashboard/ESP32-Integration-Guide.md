# Smart-Bin: connecting the ESP32 to the web dashboard

**For:** Genesis (web dashboard lead)
**From:** Brian (Android app)
**Status:** recommendations only. Every choice below is yours. When you settle one, tell Brian, and the app will be changed to match your work.

The app (v1.2) and the dashboard mockup (`Mockups/smartbin-dashboard-v12.html`) already agree on the data each bin sends. This guide writes that agreement down, explains the three routes to the bins (local Wi-Fi, Firebase, ThingSpeak), and lists the decisions still open.

---

## 1. The picture in one paragraph

Each bin is an ESP32 that reads its sensors about once a second. It serves its own small REST API on the local Wi-Fi, and that is always tried first because it is the fastest and the only route that can calibrate and simulate. It also copies its status to **Firebase** (every 5 s while someone watches, every minute otherwise) and to **ThingSpeak** (every 15 to 30 s, for history and charts). When a phone or laptop cannot reach the bin on Wi-Fi, it reads Firebase, and when Firebase gives nothing, ThingSpeak. LOCK, UNLOCK and COLLECT work over all three routes. Calibration and simulation work only over local Wi-Fi.

```
            ┌────────────── local Wi-Fi (≈1 s, everything) ──────────────┐
  ESP32 bin ├────────────── Firebase RTDB (5–60 s, LOCK/UNLOCK/COLLECT) ─┤──▶ dashboard + app
            └────────────── ThingSpeak + TalkBack (15–30 s, same 3) ─────┘
```

---

## 2. First decision: where the dashboard is served from

This choice decides what the browser is allowed to do, so it comes first.

| Option | How | Good | Watch out |
|---|---|---|---|
| **A. From the ESP32 itself** (LittleFS) | Open `http://<bin-ip>/` | One file and one origin, so no CORS. Works with no internet. | Plain `http` is not a secure context: **Web Bluetooth and WebCrypto are off**. The mockup's PIN code already allows for that (it has its own SHA-256). A dashboard served from one bin has to reach the other bins by IP, so they need CORS headers (see 4.5). The flash space is limited, so keep the page under about 300 KB, or gzip it. |
| **B. From a laptop** (`file://` or a small local server) | Double-click the HTML file, or run `py -m http.server` | Quickest while developing. `localhost` counts as secure, so Web Bluetooth works. | Every bin needs CORS headers. A `file://` page cannot use browser storage in some browsers. |
| **C. Hosted on HTTPS** (Firebase Hosting, GitHub Pages) | `https://smartbin-xxxx.web.app` | Reachable from anywhere, Web Bluetooth works, it looks finished. | An HTTPS page **cannot call `http://` bin addresses** (mixed content), so this option is cloud-only: Firebase and ThingSpeak, no calibration or simulation. |

**My suggestion, only a suggestion:** develop with **B**, and present with **A** on one bin plus **C** as a "view from anywhere" page. The app already works this way: local first, cloud as the fallback.

---

## 3. ESP32 firmware building blocks

Suggested libraries (Arduino core 3.x). Other choices are fine as long as the API in section 4 stays the same:

| Job | Option 1 | Option 2 |
|---|---|---|
| Web server | `ESPAsyncWebServer` + `AsyncTCP` (non-blocking, handles several clients at once) | Built-in `WebServer` (simpler, but blocks while it answers) |
| JSON | `ArduinoJson` v7 | — |
| Firebase | Plain REST with `HTTPClient` + `WiFiClientSecure` (matches the app, and nothing hidden) | `FirebaseClient` by mobizt (handles token refresh and streams) |
| ThingSpeak | Plain `HTTPClient` GET or POST to `api.thingspeak.com/update` | Official `ThingSpeak` library |
| Settings storage | `Preferences` (NVS) | EEPROM emulation |
| Name on the network | `ESPmDNS`, for example `smartbin-01.local` | A DHCP reservation on the router |

**Timing.** Never use `delay()` in the main loop. Give each job its own `millis()` timer, or its own FreeRTOS task pinned to core 0:

| Job | Every |
|---|---|
| Sensors (HC-SR04 median of 5, HX711, BME680/BSEC, BMI160) | 1 s (BSEC runs on its own 3 s clock) |
| Local API | Always answering |
| Firebase status write | 5 s while a watcher is fresh, otherwise 60 s, and at once on a change (see 6A) |
| Firebase command check | 2 s while watched, otherwise 10 s |
| ThingSpeak write | 30 s for two bins (see 6.3) |
| TalkBack check | 5 s |

**TLS is the slow part.** An HTTPS call to Firebase or ThingSpeak can block for 0.5 to 2 s. Put the cloud jobs in their own task so the local API and the lid stay responsive.

---

## 4. Local Wi-Fi API (the contract the app depends on)

Base: `http://<bin-ip>` (port 80). The app and the dashboard wait **1.5 s**, then treat the bin as not answering.

### 4.1 `GET /api/status`

```json
{
  "id": "bin01",
  "fill": 62.4, "distance_cm": 14.4, "weight_g": 1830,
  "temp": 29.1, "temp_raw": 31.1, "humidity": 64.0, "pressure": 1008.2,
  "iaq": 118, "iaq_acc": 3,
  "tilt": 1.2, "motion": false, "lid": "CLOSED",
  "status": "OK",
  "sim_mask": 0,
  "cal": { "d_empty": 30, "d_full": 5, "fill_th": 80, "temp_th": 40, "hum_th": 85,
           "gas_th": 200, "tilt_th": 30, "weight_cap": 4500, "auto_lock": 0,
           "servo_closed": 0, "servo_open": 90, "collect_pin": 1234,
           "pir_hold_s": 5, "buzz_s": 2, "temp_off": -2, "hum_off": 0, "press_off": 0 },
  "last_cmd": { "cmd": "LOCK", "ts": 1760500000000, "ok": true },
  "uptime_s": 5321, "rssi": -58, "fw": "1.0.0"
}
```

- `status` is one of `OK`, `LOCKED`, `TAMPER`, `COLLECTING`, `CALIBRATING`. The numbers 0 to 4 in that order are also accepted.
- `lid` is `OPEN` or `CLOSED`.
- The older names `fill_percentage`, `weight_grams`, `gas_level`, `tilt_deg`, `pir` and `lid_state` are still read, but please use the names above.
- `last_cmd`, `uptime_s`, `rssi` and `fw` are **new suggestions**. `last_cmd` lets any client confirm a command for certain (see 7). Nothing breaks without them.

### 4.2 `POST /api/command`

Body `{"cmd":"LOCK"}`. The commands:

| cmd | Effect |
|---|---|
| `LOCK` / `UNLOCK` | Servo closed or allowed to open, status `LOCKED` / `OK` |
| `CLEAR_TAMPER` | Back to `OK` after a tilt alarm *(operator)* |
| `COLLECT` / `COLLECT_DONE` | Lid open for emptying, status `COLLECTING`, then back to `OK` |
| `CALIBRATE_START` / `CALIBRATE_END` | Status `CALIBRATING`, the screen turns teal *(operator)* |
| `TARE` | Zero the load cell *(operator)* |
| `CAL_WEIGHT` + `"grams": 500` | Calibrate the load cell with a known weight *(operator)* |
| `RESET_GAS` | Clear the BSEC baseline *(operator)* |

**Recommended reply:** `200 {"ok":true,"cmd":"LOCK","status":"LOCKED"}`. For a refusal, send `409 {"ok":false,"why":"TAMPER active"}` or `403` for a role refusal (see 8).

### 4.3 `POST /api/calibrate`

The body is any subset of the `cal` keys above. Store them in NVS and reply `{"ok":true}`.

### 4.4 `POST /api/simulate`

`{"mask":5,"values":{"fill":60,"weight":2000,"gas":120,"temp":28,"humidity":60,"tamper":0,"motion":0,"lid":0}}`

Mask bits: **0** fill, **1** weight, **2** air quality, **3** temperature and humidity, **4** tamper, **5** motion, **6** lid. `mask:0` puts everything back to AUTO.

### 4.5 CORS (needed for options A with several bins, and B)

Add these headers to every reply, and answer `OPTIONS` with `204`:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type, X-SB-Op
```

If you choose the operator token from section 8, `X-SB-Op` must be in that list.

### 4.6 Finding the bins

Options: a DHCP reservation on the router (the simplest), mDNS names (`smartbin-01.local`), or each bin writing its `local_ip` to Firebase and to the ThingSpeak `status` text, which the app already reads. The app's Bluetooth setup also fills the address in.

### 4.7 Bluetooth Wi-Fi provisioning (already in the dashboard and the app)

| Name | UUID | Use |
|---|---|---|
| Service | `b1a70001-5c2e-4f8a-9d33-0a9c2b6f17c1` | |
| SSID | `b1a70002-…` | write |
| PASS | `b1a70003-…` | write |
| CTRL | `b1a70004-…` | write `1` = join, `2` = Bluetooth off |
| STAT | `b1a70005-…` | notify, the result and the IP |
| SCAN | `b1a70006-…` | read, the 2.4 GHz networks it can see |

The `…` is the same suffix as the service UUID.

### 4.8 A pairing code on the touchscreen (recommended)

As things stand, anyone near the bin could connect over Bluetooth and send it new Wi-Fi details, and the Wi-Fi password would travel unencrypted. The fix is Bluetooth's own **passkey pairing**. The ESP32 makes up a random 6-digit code, shows it on the ILI9341, and the phone or laptop must type it in before any write is accepted. This gives three things at once:

- **Proof of presence:** only someone looking at the bin's screen can pair.
- **Encryption:** after pairing, the link is encrypted (LE Secure Connections), so the SSID and password cannot be sniffed.
- **No fixed code:** a fresh code for each pairing, so there is nothing to leak or put in GitHub.

**Firmware outline (NimBLE-Arduino; the classic `BLEDevice` stack has the same parts):**

```cpp
NimBLEDevice::setSecurityAuth(true, true, true);          // bond, MITM, Secure Connections
NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);   // the bin shows, the client types
// SSID, PASS, CTRL: NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN
// STAT, SCAN:       NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN (and NOTIFY for STAT)

class SecCB : public NimBLEServerCallbacks {
  uint32_t onPassKeyDisplay() override {
    uint32_t code = esp_random() % 1000000;                // 000000 to 999999
    screenShowPairCode(code);                              // big digits, plus "Expires in 60 s"
    return code;
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    screenHidePairCode();
    if (!info.isEncrypted()) NimBLEDevice::getServer()->disconnect(info.getConnHandle());
  }
};
```

**Rules worth adding:**

| Rule | Why |
|---|---|
| Pairing only after someone taps **Pair phone** on the touchscreen (behind the collect PIN, or for operators only), and advertising stops after 60 to 120 s | Nobody can start pairing from the car park |
| The code disappears from the screen after 60 s or once pairing finishes | A photo of an old code is useless |
| After 3 failed pairings, wait 60 s before advertising again | Stops guessing |
| CTRL `2` (Bluetooth off) also clears the stored bonds, or there is a **Forget phones** key on the touchscreen | A lost phone loses access |
| Sound and RGB: the bin plays `pin_key`-style ticks while the code shows, and `op_in` or `pin_wrong` when pairing succeeds or fails | It matches the app and the dashboard |

**What the user sees:** they tap **PAIR** in the app or dashboard, the system asks for a code, they type the 6 digits from the bin's screen, and the bin chimes. Then the existing steps follow: pick a network, send, Bluetooth off.

The screens are drawn in `Mockups/smartbin-touchscreen-mockup v4.html` (slides A10 "pairing code" and "wrong codes"). The app v1.2 and dashboard v12 act the flow out with a stand-in for the bin's screen.

**Things to check early:**

- **Android app:** Android shows its own passkey box as soon as the app touches a protected characteristic, so no extra app code is needed for the dialog. Brian will add a "type the code shown on the bin" hint to the app's Bluetooth steps.
- **Web dashboard:** Web Bluetooth has no call for entering a passkey; the operating system's pairing box appears instead. That works on Android Chrome, Windows and ChromeOS. Test macOS and Linux before relying on them. Web Bluetooth also needs a secure page (`https` or `localhost`), so it **will not run from option A** in section 2.
- **Fallback if passkey pairing gives trouble:** put an app-level code on an extra AUTH characteristic (`b1a70007-…`). The bin shows a random code and ignores SSID and PASS writes until the right code is written. This still proves presence, but it **does not encrypt** the link, so the Wi-Fi password is exposed during setup. Use it only as a stopgap.

---

## 5. Firebase Realtime Database

### 5.1 Paths (the app reads exactly these)

| Path | Written by | Contents |
|---|---|---|
| `/sb/bins/<bin>/status` | bin | The `/api/status` JSON plus `ts` (ms) and `local_ip` |
| `/sb/commands/<bin>` | app or dashboard | Pushed items `{"cmd":"LOCK","ts":…,"from":"app"}` |
| `/sb/watchers/<clientId>` *(suggested, see 6A)* | app or dashboard | Written every 10 s while watching in cloud mode, so the bin knows to write every 5 s |
| `/sb/results/<pushId>` *(suggested)* | bin | The answer to each command, for the confirm |
| `/sb/bins/<bin>/cal`, `/sb/alerts/<bin>` *(suggested)* | bin | Calibration when it changes; the last 50 alerts |
| `/sb/usage/bytes_month` | bins | Bytes sent this month, for the data meter |
| `/sb_users/<uid>` | the team, by hand | The accounts allowed in |

`<bin>` is `bin01`, `bin02` and so on.

### 5.2 How a bin handles a command

1. Every 2 s, `GET /sb/commands/<bin>.json`.
2. For each item: **ignore it if `ts` is older than 60 s** (a stale tap), otherwise run it.
3. `DELETE` that item, then write the new status, with `last_cmd` filled in, straight away.

### 5.3 Sign-in

The bin signs in as its own user (email and password through the Identity Toolkit REST API, the same call the app makes) and refreshes the token before its hour runs out. Keep the bin account out of `sb_users` if you want bins to be unable to read other bins.

### 5.4 Example rules (please adapt them)

```json
{
  "rules": {
    "sb": {
      ".read": "auth != null && root.child('sb_users').child(auth.uid).exists()",
      "bins":     { "$b": { "status": { ".write": "auth != null && auth.token.email.matches(/^bin.*@/)" } } },
      "commands": { "$b": { ".write": "auth != null" } },
      "watchers": { "$c": { ".write": "auth != null" } },
      "results":  { "$r": { ".write": "auth != null" } },
      "usage":    { ".write": "auth != null" }
    },
    "sb_users": { "$uid": { ".read": "auth != null && auth.uid === $uid" } }
  }
}
```

The free (Spark) plan allows 10 GB of downloads a month and 1 GB of storage. One status write is about 600 bytes. Two bins writing every 5 s for 8 hours a day stay far under the limit.

**Dashboard side:** the v12 mockup's Firebase panel has the form, TEST and the usage meter. What is missing is **reading** `/sb/bins/*/status` when a bin does not answer locally, and pushing commands to `/sb/commands`. The app's `Cloud.kt` is a complete REST example (no SDK) you can copy the logic from. Or use the Firebase JS SDK, which is easier on an HTTPS page (option C).

---

## 6. ThingSpeak + TalkBack

### 6.1 One channel per bin

| Field | Value |
|---|---|
| field1 | Fill % |
| field2 | Weight g |
| field3 | Temperature °C |
| field4 | Humidity % |
| field5 | Air quality (IAQ) |
| field6 | Pressure hPa |
| field7 | Tilt ° |
| field8 | `motion + 2×lid_open + 4×status` (status 0 OK, 1 LOCKED, 2 TAMPER, 3 COLLECTING, 4 CALIBRATING) |
| status | The bin's local IP |

Write with `GET https://api.thingspeak.com/update?api_key=<WRITE>&field1=…&status=<ip>`.

Read the newest entry with `GET /channels/<id>/feeds/last.json?api_key=<READ>&status=true`, and history with `/channels/<id>/feeds.json?results=…`.

### 6.2 Commands through TalkBack

The app adds `LOCK`, `UNLOCK` or `COLLECT` to the bin's TalkBack queue. The bin polls `POST /talkbacks/<id>/commands/execute.json` with `api_key=<TB_KEY>` every 5 s, which returns the next command and removes it in one call, then writes its status at once.

### 6.3 Limits that matter

- At most one write every **15 s** per channel on the free licence.
- **3 million messages a year** per account. One bin writing every 20 s uses about 1.58 million a year, so **two bins should write every 30 s or slower**. TalkBack polls do not count as channel writes, but keep them sensible.
- ThingSpeak's own charts (`/channels/<id>/charts/1`) can be put straight into the dashboard with an `<iframe>`, for free history graphs. That is optional.

---

## 6A. Saving the free allowances (the Garbage-Guard way)

Garbage-Guard kept a whole semester of demos inside Firebase's free plan by following one idea: **send fast only while someone is looking, slow otherwise, and at once when something changes.** The same rules fit Smart-Bin, and they matter even more on an ESP32, where every HTTPS call costs time and power.

### Firebase (Spark plan: 10 GB downloaded a month, 1 GB stored, 100 connections, no per-operation limit)

**1. Watchers decide the speed.** While the app or dashboard is open in cloud mode, it writes `/sb/watchers/<clientId>` every 10 s:

```json
{ "ts": {".sv": "timestamp"}, "device": "Galaxy A35", "kind": "app", "mode": "foreground", "bin": "bin01" }
```

`{".sv":"timestamp"}` makes Firebase fill in its own clock, so phone clocks don't matter. The client `DELETE`s the entry when it leaves cloud mode, closes, or spends more than a minute in the background. Every 30 s the bin reads `/sb/watchers.json?orderBy="ts"&startAt=<now-30000>`, which returns only the fresh entries (add `".indexOn": "ts"` under `watchers` in the rules). It only needs to know whether that list is empty:

| Somebody watching? | Bin writes `/sb/bins/<bin>/status` | Bin checks `/sb/commands/<bin>` |
|---|---|---|
| Yes | Every 5 s | Every 2 s |
| No | Every 60 s (a heartbeat) | Every 10 s |
| Always | **At once** when the status, an alert, the lid or the lock changes | — |


**2. Write only what changed.** Keep the last JSON you sent. If nothing moved more than a small step (fill ±1 %, weight ±20 g, temperature ±0.2 °C, humidity ±1 %, IAQ ±5), skip the write and let the heartbeat carry it. Use `PATCH` with only the changed fields when that is smaller than the whole object.

**3. Keep the payload small.** Round the numbers (one decimal at most). Leave `cal` out of the cloud status; it changes rarely, so write it to `/sb/bins/<bin>/cal` only when it changes. Without `cal` a status is about 350 bytes instead of about 600.

**4. History is not Firebase's job.** Charts over hours and days come from ThingSpeak, which stores them for free. Firebase keeps only the **latest** status per bin, plus the last 50 alerts under `/sb/alerts/<bin>` (trim older ones). Read those with `orderBy="$key"&limitToLast=20`.

**5. Commands run once.** The bin `DELETE`s a command **before** it runs it, so a crash halfway cannot repeat it. It then writes the answer to `/sb/results/<pushId>` as `{"ok":true,"cmd":"LOCK","ts":…}`. The client polls that result every second for up to 8 s, which gives the app's confirm or time-out (section 7). Clients `POST`, never `PUT`, so each command gets its own time-ordered key.

**6. Count what you send.** The bin adds up the bytes it writes, multiplied by the number of fresh watchers (each one downloads it). Every 10 minutes, and only if the total changed, it writes:

```json
/sb/usage = { "month": "2026-10", "down_bytes": 128849018, "limit_bytes": 10737418240,
              "fraction": 0.012, "level": "ok", "estimate": true }
```

`level` is `ok` under 70 %, `warn` from 70 %, `near` from 90 % and `bad` at 100 %, in green, amber, orange and red. Show it as "**about** 0.12 GB of 10 GB", because it is an estimate; the exact figure is only in the Firebase console. Clients read it at most once a minute. The app's data meter already reads `/sb/usage/bytes_month`, so either keep that name or tell Brian about the change.

**7. Brakes the bin applies by itself:** at 90 % the watched rate slows from 5 s to 15 s. At 100 %, status writes drop to the 60 s heartbeat, but alerts and commands keep working.

**8. Streaming is optional.** Instead of polling, a browser can keep one `EventSource` on `/sb/bins.json?auth=…` and receive `put` and `patch` events. That is cheaper for the dashboard, and the Firebase JS SDK does it for you. Polling the status every 5 s is also fine and simpler.

**9. A separate project per team demo.** Limits count per Firebase project, so Smart-Bin should not share Garbage-Guard's project.

**Rough monthly use (one phone watching for 4 hours a day, two bins):**

| What | Size | Per month |
|---|---|---|
| Status every 5 s while watched | ~350 B × 2 bins | ~60 MB |
| Heartbeat every 60 s otherwise | ~350 B × 2 bins | ~25 MB |
| Watcher writes and command polls | tiny | < 5 MB |
| **Total** | | **about 1 % of the 10 GB** |

### ThingSpeak (free licence: 3 million messages a year, 1 write per 15 s per channel)

1. **One write carries everything.** All 8 fields plus `status` go in **one** `update` call, so a reading costs one message, not eight.
2. **Fixed pace, sent early only for alerts.** Write every **30 s** with two bins (about 2.1 million messages a year for both, under the 3 million). If an alert starts, send the next write at once, as long as 15 s have passed since the last one; otherwise send it as soon as 15 s are up.
3. **Skip identical writes, but not forever.** If no field moved by the small steps above, skip the write, but always write at least every 5 minutes so the charts keep a steady line and "last seen" stays honest.
4. **TalkBack polls are separate.** Polling `commands/execute.json` does not use channel messages, but every 5 s is plenty. Slow it to 15 s when Firebase shows no watcher, because nobody is likely to be sending commands then.
5. **Read cheaply.** Clients read `feeds/last.json` for the current state, and `feeds.json?results=N` (or `?minutes=`, `?average=10` for averaged chart points) for history, at most every 15 s. Reads don't use the message allowance, but there is no point in reading faster than the bin writes.
6. **Estimate the year.** ThingSpeak has no call that reports usage, so the app estimates it from how fast `entry_id` rises. The dashboard can do the same: (entries in the last hour × 24 × 365) per channel, summed and compared with 3 million.
7. **Bulk upload, if you want a faster history.** The bin can buffer readings and send them every 2 to 5 minutes with `bulk_update.json`. Each entry still counts as one message, but there are far fewer HTTPS calls, which saves the ESP32 time and power. The trade-off is that ThingSpeak's view lags by that buffer.

### On the ESP32 itself

- **Reuse TLS connections.** Keep one `WiFiClientSecure` per host with keep-alive, rather than a new handshake every call. Each handshake is the expensive part, about 0.5 to 2 s and around 40 KB of RAM.
- **Put cloud work in one FreeRTOS task** with a small queue, so the local API, lid and screen never wait for the internet.
- **Back off when the internet is down:** retry after 5 s, then 15, 30, and every 60 s, so a dead router doesn't make the bin spend all its time on timeouts.

---

## 7. Confirming a command (how the app shows it)

The app keeps a command key "waiting" until the bin confirms it:

- **Local Wi-Fi:** the `200 {"ok":true}` reply is the confirmation.
- **Firebase / ThingSpeak:** the app watches the bin's next status. If it shows the new state (or `last_cmd.cmd` matches with `ok:true`) within **8 s**, the key flashes green with a "confirmed" sound. Otherwise it flashes red with "did not confirm". The demo mode acts this out by confirming after 4 s.

Recommendation: write the status **at once** after any command, so the confirmation lands in 2 to 5 s.

---

## 8. Operator and collector security

The app and dashboard v12 now **start as collector**. Operator needs a 4 to 8 digit PIN. Only a salted hash is stored, 5 wrong tries start a 30 s wait that doubles each time, operator drops back to collector after 2 min away, and only an operator can change the PIN (after typing the current one).

**Be honest in the report:** a PIN checked inside a web page or an app is a **deterrent, not real security**. Anyone with developer tools can skip it, or send `POST /api/command` directly. To really protect operator actions, choose one of these:

| Level | What it means |
|---|---|
| 0. UI only (what exists now) | Good enough for a class demo. |
| 1. Bin checks a token | `POST /api/auth {"pin":"…"}`. The ESP32 checks the hash it keeps in NVS and returns a random token that expires after 2 min of no use. Operator-only commands (calibrate, simulate, CLEAR_TAMPER, TARE, CAL_WEIGHT, RESET_GAS, CALIBRATE_*) need the header `X-SB-Op: <token>`, otherwise `403`. LOCK, UNLOCK and COLLECT stay open for collectors. |
| 2. Firebase roles | `/sb_users/<uid>/role = "operator" \| "collector"`, and the rules only allow operator commands from operator accounts. |

If you choose level 1, tell Brian, and the app will send the header and the PIN to the bin instead of checking it only on the phone. One shared PIN on the bin would also mean the app and the dashboard use the same operator PIN, which is tidier than each device having its own.

Also keep the **collect PIN** (the touchscreen's) out of GitHub: it goes in `secrets.h`, which is git-ignored.

---

## 9. Sounds: dashboard, app and the bin's passive buzzer

### 9.1 Dashboard and app (done)

Dashboard v12 plays the same tunes as the app, built live with Web Audio from the same table as `Android App/tools/make_sounds.py`. Rising pitch means on, forward or OK. Falling means off, back or no. A speaker key in the header mutes them, and the choice is remembered. Laptops have no vibration motor, so the dashboard has sound only. The phone also vibrates, with its own rhythm per event.

### 9.2 The bin's buzzer: a sound for every press and event (suggestion)

The aim is that the bin, the phone and the dashboard **sound like one system**: a full bin, a tamper or a pickup has the same tune shape everywhere, so people learn it once.

**First check: is the module passive?** Only a **passive** buzzer can play different notes, because the ESP32 sends it a square wave and the wave's frequency is the pitch. An active buzzer has its own oscillator and can only make one beep. To tell them apart, power the module (VCC to 3.3 V, GND) and touch IN to GND. An active one beeps steadily; a passive one only clicks. A sealed top with a sticker usually means active; a passive one shows its green board underneath. If yours is active, a passive module costs the same and looks alike.

**Pitch.** Small passive buzzers are thin below about 1 kHz and loudest around 2 to 4 kHz. Garbage-Guard's module was loudest at **2000 Hz**. So the bin keeps each app tune's **rhythm and shape** but moves it up into **1500 to 2800 Hz**. Tune the exact numbers by ear on the bench.

**Coverage.** The tables cover every screen and key in `Mockups/smartbin-touchscreen-mockup v4.html` (A1 to A10 and the live sandbox) and every event the firmware reports. A key that only moves between screens (a network row, RETRY, DONE on the Connected screen) uses the key tick. A screen's own tune is the event that opened it. If you add a screen or event, give it a row here.

**Every moment has its own tune.** No two rows below share one, and they match the app and dashboard: the app's sound table (`Android App/tools/make_sounds.py`) has the same moments, and each also has its own vibration on the phone.

**Priority.** 5 is an alarm and 0 is a key tick. A higher priority sound cuts off a lower one. A lower one that arrives while a higher one plays is **dropped, not queued**, so sounds never pile up.

Each tune is a list of `Hz/ms`; `r` is a rest, and `a→b` is a sweep.

#### Touchscreen presses

| When | Tune | Pri | Default |
|---|---|---|---|
| Any key (i, theme, Wi-Fi chip, list rows) | 2400/15 | 0 | on |
| PIN keypad digit | 2600/12 | 0 | on |
| PIN delete | 2000/15 | 0 | on |
| CANCEL, back to the main screen | 2300/30, 1800/45 | 0 | on |
| Theme to light / to dark | 2300/35, 2700/50 · 2000/35, 1700/50 | 0 | on |
| Open Info or Wi-Fi setup | 1800/30, 2300/40 | 0 | on |
| Collect PIN correct (pickup starts) | 1600/60, 1900/60, 2200/60, 1900/60, 2200/120 | 3 | on |
| Collect PIN wrong | 1700/60, r30, 1700/60, r30, 1500/150 | 3 | on |
| DONE pressed (collection complete) | 1800/70, 2100/70, 2400/70, 2800/200 | 3 | on |

#### The lid and the bin's own events

| When | Tune | Pri | Default |
|---|---|---|---|
| Boot finished, sensors OK | 1600/70, 1900/70, 2200/70, 2800/180 | 1 | on |
| Boot with a sensor missing | 1600/70, 1900/70, r40, 1700/100, r60, 1500/210 | 4 | on |
| Lid opens for a hand | 1800/25, 2200/30 | 0 | **off** (it would sound every few minutes) |
| Lid closes | 2200/25, 1800/30 | 0 | **off** |
| Hand waved while LOCKED (lid stays shut) | 1600/60, r40, 1500/120 | 2 | on |
| Locked (by the app, dashboard or auto-lock) | 2000/40, r20, 1600/120 | 3 | on |
| Unlocked | 1600/40, r20, 2000/120 | 3 | on |
| Calibration starts | 2300/40, r50, 2300/40, r50, 2300/40, r50, 2700/120 | 2 | on |
| Each calibration step OK, TARE, CAL_WEIGHT, RESET_GAS | 2400/40 | 1 | on |
| Settings saved, calibration ends | 2200/60, 2600/100 | 2 | on |
| Simulation on / off | 1600/60, 1800/60, 2000/60, 2300/140 · the reverse | 2 | on |
| Calibration cancelled on the bin (CANCEL on the teal screen) | 2400/40, r40, 1900/120 | 2 | on |
| Air sensor ready (BME680 accuracy reaches 3 after warm-up) | 2000/40, 2400/70 | 1 | on |
| Fill passes 50 % (FILLING, amber) | 2100/30, r40, 2100/30 | 1 | **off** (the screen already turns amber) |
| Back to the main screen 30 s after DONE | 2400/30, 2000/50 | 1 | on |

#### Alarms (the loud ones)

| When | Tune | Pri | Repeats |
|---|---|---|---|
| **Full** (the URGENT screen) | 2000/180, r80, 2000/180, r80, 2000/180, r200, 2600/420 | 5 | Every 30 s until COLLECT, LOCK, or the fill drops. Plays for at least `buzz_s` seconds |
| **Tamper**, countdown | 2000/60 tick each second, 2400/60 for the last 3 s | 5 | Until it settles or latches |
| **Tamper**, latched | 1600→2800/340, 2800→1600/340, twice | 5 | Every 10 s until the dashboard clears it |
| Temperature, humidity or air over the limit | 2400/120, 1900/120, 2400/120, 1900/260 | 4 | Once, then every 5 min while it stays over |
| A sensor stops answering (BME680, HX711, HC-SR04) | 1900/100, r60, 1500/210 | 4 | Once |
| Tamper settles by itself, or the dashboard clears it | 1900/60, 2300/60, 2600/120 | 4 | Once, and the tamper alarm stops |
| A reading goes back under its limit | 2600/50, 2200/100 | 2 | Once |
| A sensor answers again | 1500/80, 2000/150 | 3 | Once |

#### Network and Bluetooth

| When | Tune | Pri | Default |
|---|---|---|---|
| Wi-Fi joined, or back after a drop | 1800/70, 2200/70, 2700/150 | 2 | on |
| Wi-Fi lost | 2700/90, 2200/90, 1800/190 | 3 | on, once |
| Cloud (Firebase) cannot be reached | 2200/60, r30, 2400/60, r30, 1900/130 | 2 | on, once |
| Cloud reachable again | 1900/60, r30, 2400/60, r30, 2700/120 | 2 | on, once |
| A command arrives remotely (app or dashboard) | That command's own tune (Lock, Unlock, Pickup) | 3 | on |
| Command could not be carried out (refused or failed) | 1900/80, r30, 1700/80, r30, 1500/200 | 3 | on |
| Bluetooth on, waiting to pair | 1800/30, r30, 1800/30, 2300/60 | 1 | on |
| **Pairing code shown** (4.8) | 2400/30, r60, 2400/30 | 2 | on |
| Paired (the code matched) | 2300/40, 2800/40, 2300/40, 2800/140 | 3 | on |
| Wrong pairing code | 1800/50, r25, 1800/50, 1500/140 | 3 | on |
| 3 wrong codes, pairing stopped | 2200/180, r50, 1500/320 | 4 | on |
| Joined the Wi-Fi sent over Bluetooth | 1700/60, 2000/60, 2400/60, 2800/180 | 3 | on |
| Joining failed | 1600/70, r40, 1500/120 | 3 | on |
| Bluetooth off (from the app or dashboard) | 2300/50, 1800/50, r25, 1500/140 | 2 | on |
| Bluetooth switched itself off after 5 min unused | 2300/50, 1800/50, r25, 1500/60, r60, 1500/60 | 1 | on |

#### Rules that keep it from causing trouble

- **It never slows the bin.** One FreeRTOS task plays tunes from a queue of length 1 (the priority rule), so sensors, the lid, the screen and the API never wait for a sound.
- **COLLECTING mutes the alarms** (the collector is already there). Keys and the DONE tune still play.
- **CALIBRATING keeps it quiet** apart from calibration sounds, so a person standing next to the bin isn't startled.
- **Quiet hours** (optional, for example 22:00 to 06:00) silence everything below priority 5. The time comes from NTP. If the clock isn't set yet, quiet hours are not applied.
- **Alarms repeat** as listed, with `buzz_s` (already among the calibration values) as the minimum length of the full-bin alarm.

#### Wiring and code

- **Pin.** Use a free GPIO that is **not a boot strapping pin** (avoid 0, 2, 5, 12 and 15) and is not used by the ILI9341/XPT2046 SPI bus, the HX711, the servo or the RGB LED. Check `Hardware/Smart-bin_Schematic_Diagram.pdf` for what is free; 25, 26, 27, 32 and 33 are often good choices.
- **Many modules are "low level trigger"** (sound while IN is low). Hold the pin **high** when silent, and add a 10 kΩ pull-up from IN to 3.3 V if it chirps at boot. Power the module from **3.3 V**: on 5 V a 3.3 V high may not switch it off.
- **Its own LEDC timer.** `ledcWriteTone()` changes the frequency of the whole LEDC **timer**. If the buzzer shares a timer with the **servo** (50 Hz) or the screen backlight, the servo will jitter or the lid will move by itself. Give the buzzer a timer of its own; with the `ESP32Servo` library, reserve its timers with `ESP32PWM::allocateTimer()` first.
- **Volume** is the duty cycle: 50 % is the loudest, 10 to 25 % is softer. Offer Low, Medium and High.

```cpp
// Core 3.x: ledcAttach(pin, freq, bits); ledcWriteTone(pin, hz); hz 0 = silence
struct Note { uint16_t hz, ms, hzEnd; };           // hzEnd != 0 makes a sweep
struct Tune { const Note* n; uint8_t len; uint8_t pri; };
static QueueHandle_t q;                             // xQueueCreate(1, sizeof(Tune))
static volatile uint8_t playing = 0;                // priority of the tune now playing

void buzz(const Tune& t) {                          // call from anywhere, never blocks
  if (!cfg.buzzOn || (quietNow() && t.pri < 5) || (collecting && t.pri == 5)) return;
  if (t.pri < playing) return;                      // lower than what plays: dropped
  xQueueOverwrite(q, &t);                           // equal or higher: replaces it
}

void buzzerTask(void*) {
  Tune t;
  for (;;) {
    if (xQueueReceive(q, &t, portMAX_DELAY) != pdTRUE) continue;
    playing = t.pri;
    for (uint8_t i = 0; i < t.len; i++) {
      if (uxQueueMessagesWaiting(q)) break;         // a higher one arrived: stop this one
      const Note& n = t.n[i];
      if (!n.hz) { ledcWriteTone(BUZZ_PIN, 0); vTaskDelay(pdMS_TO_TICKS(n.ms)); continue; }
      if (n.hzEnd) {                                // sweep in 10 ms steps
        for (uint16_t s = 0; s < n.ms; s += 10) { ledcWriteTone(BUZZ_PIN, n.hz + (int32_t)(n.hzEnd - n.hz) * s / n.ms); vTaskDelay(pdMS_TO_TICKS(10)); }
      } else { ledcWriteTone(BUZZ_PIN, n.hz); ledcWrite(BUZZ_PIN, duty()); vTaskDelay(pdMS_TO_TICKS(n.ms)); }
    }
    ledcWriteTone(BUZZ_PIN, 0); digitalWrite(BUZZ_PIN, HIGH);   // idle high for low-trigger modules
    playing = 0;
  }
}
```

#### Settings and API (so the app and dashboard can control it)

| Key in `/api/calibrate` and `cal` | Values | Default |
|---|---|---|
| `buzz_on` | 0 or 1 | 1 |
| `buzz_vol` | 1 Low, 2 Medium, 3 High | 2 |
| `buzz_ui` | 0 or 1, key ticks and the lid | 1 (lid sounds stay off unless `buzz_lid` = 1) |
| `buzz_repeat_s` | 10 to 300 | 30 |
| `quiet_on`, `quiet_from`, `quiet_to` | 0 or 1, minutes after midnight | 0, 1320, 360 |
| `buzz_s` | Already exists: the minimum length of the full-bin alarm | 2 |

Add `POST /api/command {"cmd":"BUZZ_TEST","sound":"full"}` so the person setting it up can hear any tune from the dashboard or app. If you build these, tell Brian: the app's Settings will get a **Bin buzzer** group (on, volume, key sounds, quiet hours, test), the same as Garbage-Guard's Power card.

---

## 10. Testing with no hardware

`Android App/tools/smartbin_mock_server.py` plays the bins, a fake Firebase and a fake ThingSpeak on your laptop:

```
py "Android App/tools/smartbin_mock_server.py" --bins 2 --cloud 8090 --ts 8095
```

Point the dashboard at `127.0.0.1:8081` and `:8082` for local Wi-Fi, `http://127.0.0.1:8090` for Firebase, and `http://127.0.0.1:8095` for ThingSpeak (channels 1001 and 1002, TalkBack 2001 and 2002, any key).

---

## 11. Where dashboard v12 still differs from the app

| App has | Dashboard v12 | Suggestion |
|---|---|---|
| Link chip with every route that answers (Wi-Fi green, Firebase orange, ThingSpeak blue) | Local Wi-Fi only ("Connected", "Demo data") | Add once you choose how the dashboard reads the cloud (sections 2 and 5) |
| Reads Firebase, then ThingSpeak, when a bin is not on Wi-Fi | Firebase form and TEST only | Section 5 |
| Per-bin ThingSpeak channel, read key and TalkBack fields | None | Section 6 |
| Command waits and confirms or times out (8 s) | Local reply only (confirm or "did not confirm" sound and toast) | Section 7 |
| Demo "pretend links" | None | Optional, only for presenting |
| Operator PIN, auto-lock, sounds | **Done in v12** | — |

---

## 12. Decisions to send back to Brian

Please reply with your choice for each. The app will be updated to match:

1. Where the dashboard is served from (A, B, C, or a mix).
2. Web server library, and whether `/api/command` returns the reply in 4.2.
3. Whether to add `last_cmd`, `uptime_s`, `rssi` and `fw` to the status.
4. Firebase: plain REST or the SDK, the watcher and results paths (6A), the usage format, and the final rules.
5. ThingSpeak write interval (30 s suggested), and whether to put its charts in the dashboard.
6. Operator security level (0, 1 or 2), and if 1, the `/api/auth` details.
7. How bins are found: DHCP reservation, mDNS, or the cloud `local_ip`.
8. Bluetooth pairing: passkey on the touchscreen (4.8), the AUTH-code fallback, or none. Also, who can start pairing on the bin.
9. The bin buzzer: passive module confirmed, which pin, and which of the 9.2 tunes and settings you keep.
10. Any field or command you rename. The app's names are listed in sections 4 to 6.
