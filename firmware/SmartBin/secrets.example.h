#pragma once

// Copy this file to secrets.h in the same folder and fill it in.
// secrets.h is git-ignored. Never commit real keys, passwords or the Collect PIN.

// Wi-Fi (2.4 GHz only). Can also be changed later from WI-FI SETUP on the touchscreen.
#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"

// Firebase Realtime Database. Empty = Firebase off (local Wi-Fi only).
// See firmware/README.md section 4. The bin signs in with its own account,
// for example bin01@your-project.test.
#define FIREBASE_URL ""          // https://<project>-default-rtdb.asia-southeast1.firebasedatabase.app
#define FIREBASE_API_KEY ""      // AIza... (Project settings > Web API key)
#define FIREBASE_EMAIL ""
#define FIREBASE_PASSWORD ""

// ThingSpeak, one channel per bin. Empty = ThingSpeak off. See firmware/README.md section 5.
#define THINGSPEAK_SERVER "https://api.thingspeak.com"
#define THINGSPEAK_WRITE_KEY_BIN_A ""
#define THINGSPEAK_WRITE_KEY_BIN_B ""
#define THINGSPEAK_TALKBACK_ID_BIN_A ""
#define THINGSPEAK_TALKBACK_KEY_BIN_A ""
#define THINGSPEAK_TALKBACK_ID_BIN_B ""
#define THINGSPEAK_TALKBACK_KEY_BIN_B ""

// Default Collect PIN (4 digits). It can be changed later from the dashboard or app.
#define COLLECT_PIN "0000"
