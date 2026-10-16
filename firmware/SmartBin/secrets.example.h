#pragma once

// Copy this file to secrets.h in the same folder and fill it in.
// secrets.h is git-ignored. Never commit real keys, passwords or the Collect PIN.

#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"

// Firebase Realtime Database. Leave FIREBASE_URL empty to switch Firebase off.
// The bin signs in as its own user (Authentication > Email/Password), for
// example bin01@your-project.test, so the rules can tell bins from people.
#define FIREBASE_URL ""          // https://<project>-default-rtdb.asia-southeast1.firebasedatabase.app
#define FIREBASE_API_KEY ""      // AIza... (Project settings > Web API key)
#define FIREBASE_EMAIL ""
#define FIREBASE_PASSWORD ""

// ThingSpeak. One channel per bin. Leave a write key empty to switch it off.
#define THINGSPEAK_SERVER "https://api.thingspeak.com"
#define THINGSPEAK_WRITE_KEY_BIN_A "WRITE_KEY_BIN_A"
#define THINGSPEAK_WRITE_KEY_BIN_B "WRITE_KEY_BIN_B"
#define THINGSPEAK_TALKBACK_ID_BIN_A ""
#define THINGSPEAK_TALKBACK_KEY_BIN_A ""
#define THINGSPEAK_TALKBACK_ID_BIN_B ""
#define THINGSPEAK_TALKBACK_KEY_BIN_B ""

// Default Collect PIN (4 digits). The operator can change it later from the
// dashboard or app; the new value is kept on the bin.
#define COLLECT_PIN "0000"
