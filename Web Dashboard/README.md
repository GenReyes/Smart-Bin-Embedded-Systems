# Smart-Bin web dashboard

`smartbin-dashboard.html` is the working operator dashboard. It has the screens, sounds and operator PIN of the group's design, plus real links to the bins:

1. **The bin's own Wi-Fi** first. It keeps one push connection per bin (`/api/events`) and falls back to reading `/api/status` every second.
2. **Firebase** when a bin does not answer on Wi-Fi.
3. **ThingSpeak** when Firebase gives nothing, and for the 24-hour chart history.

LOCK, UNLOCK and COLLECT work over all three. Calibration, simulation, tare and the other operator commands need the bin's Wi-Fi, the same as in the app.

A bin is **live** once it has an IP address, a Firebase cloud name (with Firebase saved), or a ThingSpeak channel and read key. A live bin never shows made-up numbers: when it stops answering it keeps its last real reading, shows OFFLINE and a red banner. Made-up bins only appear with the **DEMO** button in the top bar switched on. With DEMO off (the default once a real bin is linked) the dashboard shows only real bins, and a bin with no link is not kept. DEMO on adds three "Demo Bin" cards next to the real ones, so the class demo still works with no hardware. Opening the page from a bin (`http://<bin IP>`) links that bin automatically.

## Open it

| Way | How | Good for |
|---|---|---|
| From a bin | `http://<bin-ip>/` or `http://smartbin-01.local/` | Presenting. The bin serving the page is added by itself. |
| From a laptop | In this folder run `py -m http.server 8000`, open `http://localhost:8000/smartbin-dashboard.html` | Developing. Web Bluetooth works on `localhost`. |
| Double-click the file | `file://` | Quick look. Works, but Web Bluetooth is off. |

A page on `https://` (GitHub Pages, Firebase Hosting) cannot call `http://` bin addresses, so there it uses Firebase and ThingSpeak only.

## Set up a bin

Overview, the gear on the bin's card (or SET on a tile):

| Field | Example |
|---|---|
| IP address | `192.168.1.101` (shown on the bin's screen and its Info screen) |
| Firebase cloud name | `bin01` |
| ThingSpeak channel ID / read key | from the channel's API Keys tab |
| TalkBack ID / API key | only if you use TalkBack for commands |

Then, in **Settings > Cloud backup (Firebase)**, type the database URL, Web API key and an account that is in `sb_users`, press **TEST AND SAVE**. The account is kept in this browser only. **SEND TO BINS** gives the account typed there to every bin that answers on Wi-Fi (use the bins' own `bin01@...` account for that, see `firmware/README.md`). The ThingSpeak server field is only changed for the test server below.

The link chip in the header shows which links are in use (Wi-Fi green, Firebase orange, ThingSpeak blue), and each card shows WI-FI, CLOUD or TS. The Cloud panel lists each bin's link and how long ago its last reading came, and the Firebase data meter once the bins report it.

## Commands and confirmation

- On Wi-Fi the bin's reply is the confirmation. A refusal shows the reason (for example "TAMPER active").
- Through Firebase the dashboard pushes to `/sb/commands/<bin>`, then waits up to 8 s for the bin's answer in `/sb/results/<id>` or its next status, and plays the confirm or "did not confirm" sound.
- Through TalkBack it waits up to 45 s, because the bin checks TalkBack every 5 s and ThingSpeak updates every 15 to 30 s.

While it reads any bin through Firebase, the dashboard writes `/sb/watchers/<this browser>` every 10 s, so the bins switch to their fast pace, and removes it when the tab is hidden or closed.

## Test without the bins

```
py "Android App/tools/smartbin_mock_server.py" --bins 2 --cloud 8090 --ts 8095
```

- Wi-Fi: bins at `127.0.0.1:8081` and `127.0.0.1:8082`.
- Firebase: URL `http://127.0.0.1:8090`, any key, any email, a password of 6+ characters. Add `--no-local` to the command to make the dashboard fall back to it.
- ThingSpeak: server `http://127.0.0.1:8095`, channels `1001` and `1002`, TalkBack `2001` and `2002`, any keys.

The mock server now sends CORS headers and answers the Firebase PUT, PATCH and DELETE calls, so the dashboard can run against it in a browser.

This version was checked in headless Chrome against the mock server on all three links (readings arrive, LOCK and UNLOCK reach the bin and confirm) and against a test bin that pushes `/api/events`, with no script errors.

## Choices behind it

| Topic | Choice |
|---|---|
| Where it is served from | From each bin (`http://<bin-ip>/`, packed into `firmware/SmartBin/dashboard_gz.h`) for presenting, `localhost` for developing. A page on `https://` works with the cloud only. |
| Firebase | Plain REST, polled every 5 s. Watchers at `/sb/watchers/<client>`, results at `/sb/results/<push id>`, alerts at `/sb/alerts/<bin>` (newest 50), usage at `/sb/usage`. Rules in `firmware/firebase.rules.json`. |
| ThingSpeak | Charts read `feeds.json` into the dashboard's own chart, no iframe. |
| Operator security | PIN in the dashboard and the app. The bin refuses unsafe commands itself (COLLECT during tamper or calibration, LOCK during a pickup). |
| Finding the bins | `smartbin-01.local` / `smartbin-02.local` (mDNS), the IP on the screen, and `local_ip` in Firebase and the ThingSpeak status. A DHCP reservation on the router is still the most reliable. |

After `smartbin-dashboard.html` changes, run `py firmware/tools/embed_dashboard.py` and upload the firmware again so the bins serve the new page.
