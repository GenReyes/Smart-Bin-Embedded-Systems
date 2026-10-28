#include "state.h"
#include "sensors.h"
#include <Preferences.h>
#include <cstddef>
#include <WiFi.h>
#include <sys/time.h>
#include "secrets.h"

const char* const STATUS_NAMES[5] = {"OK", "LOCKED", "TAMPER", "COLLECTING", "CALIBRATING"};

BinState st;
Cal cal;
Sim sim;
NetCfg net;
SemaphoreHandle_t stLock;
QueueHandle_t cmdQueue, resultQueue, alertQueue;
TaskHandle_t hControl = nullptr, hSensor = nullptr, hUi = nullptr, hCloud = nullptr, hPush = nullptr;

static Preferences prefs;
static SemaphoreHandle_t prefsLock;

void stateInit() {
  stLock = xSemaphoreCreateMutex();
  prefsLock = xSemaphoreCreateMutex();
  cmdQueue = xQueueCreate(8, sizeof(Command));
  resultQueue = xQueueCreate(8, sizeof(CmdResult));
  alertQueue = xQueueCreate(8, sizeof(AlertEv));
  cal.collect_pin = (uint16_t)atoi(COLLECT_PIN);
  loadCal();
  loadTouchCal();
  loadNet();
}

void lockState() { xSemaphoreTake(stLock, portMAX_DELAY); }
void unlockState() { xSemaphoreGive(stLock); }

BinState snapshot() {
  lockState();
  BinState s = st;
  unlockState();
  return s;
}

Cal calSnapshot() {
  lockState();
  Cal c = cal;
  unlockState();
  return c;
}

Sim simSnapshot() {
  lockState();
  Sim s = sim;
  unlockState();
  return s;
}

void notifyAll(uint32_t bits) {
  TaskHandle_t hs[] = {hUi, hCloud, hPush};
  for (TaskHandle_t h : hs)
    if (h) xTaskNotify(h, bits, eSetBits);
}

void publish(uint32_t extraBits) {
  lockState();
  st.version++;
  unlockState();
  notifyAll(EV_STATE | extraBits);
}

static void prefsBegin(bool ro) {
  xSemaphoreTake(prefsLock, portMAX_DELAY);
  prefs.begin("smartbin", ro);
}
static void prefsEnd() {
  prefs.end();
  xSemaphoreGive(prefsLock);
}

void saveCal() {
  Cal c = calSnapshot();
  prefsBegin(false);
  prefs.putBytes("cal", &c, sizeof(c));
  prefsEnd();
}

void loadCal() {
  prefsBegin(true);
  size_t n = prefs.getBytesLength("cal");
  if (n == sizeof(Cal)) {
    prefs.getBytes("cal", &cal, sizeof(Cal));
  } else if (n == offsetof(Cal, lid_close_s)) {
    // Saved by firmware from before lid_close_s and tilt_boot: keep every value and add the new defaults.
    prefs.getBytes("cal", &cal, n);
    Cal d;
    cal.lid_close_s = d.lid_close_s;
    cal.tilt_boot = d.tilt_boot;
  }
  prefsEnd();
}

TouchCal touchCal;
bool touchCalCustom = false;

void saveTouchCal() {
  prefsBegin(false);
  prefs.putBytes("touch", &touchCal, sizeof(touchCal));
  prefsEnd();
  touchCalCustom = true;
}

void loadTouchCal() {
  prefsBegin(true);
  if (prefs.getBytesLength("touch") == sizeof(TouchCal)) {
    prefs.getBytes("touch", &touchCal, sizeof(TouchCal));
    touchCalCustom = true;
  }
  prefsEnd();
}

void resetTouchCal() {
  touchCal = TouchCal();
  prefsBegin(false);
  prefs.remove("touch");
  prefsEnd();
  touchCalCustom = false;
}

void saveNet() {
  prefsBegin(false);
  prefs.putString("ssid", net.ssid);
  prefs.putString("pass", net.pass);
  prefs.putString("fbUrl", net.fbUrl);
  prefs.putString("fbKey", net.fbKey);
  prefs.putString("fbEmail", net.fbEmail);
  prefs.putString("fbPass", net.fbPass);
  prefsEnd();
}

void loadNet() {
  prefsBegin(true);
  net.ssid = prefs.getString("ssid", WIFI_SSID);
  net.pass = prefs.getString("pass", WIFI_PASSWORD);
  net.fbUrl = prefs.getString("fbUrl", FIREBASE_URL);
  net.fbKey = prefs.getString("fbKey", FIREBASE_API_KEY);
  net.fbEmail = prefs.getString("fbEmail", FIREBASE_EMAIL);
  net.fbPass = prefs.getString("fbPass", FIREBASE_PASSWORD);
  prefsEnd();
  net.tsServer = THINGSPEAK_SERVER;
#if BIN_NUMBER == 1
  net.tsWriteKey = THINGSPEAK_WRITE_KEY_BIN_A;
  net.tbId = THINGSPEAK_TALKBACK_ID_BIN_A;
  net.tbKey = THINGSPEAK_TALKBACK_KEY_BIN_A;
#else
  net.tsWriteKey = THINGSPEAK_WRITE_KEY_BIN_B;
  net.tbId = THINGSPEAK_TALKBACK_ID_BIN_B;
  net.tbKey = THINGSPEAK_TALKBACK_KEY_BIN_B;
#endif
  if (net.tsWriteKey.startsWith("WRITE_KEY")) net.tsWriteKey = "";
}

uint8_t loadTheme() {
  prefsBegin(true);
  uint8_t t = prefs.getUChar("theme", 0);
  prefsEnd();
  return t;
}

void saveTheme(uint8_t dark) {
  prefsBegin(false);
  prefs.putUChar("theme", dark);
  prefsEnd();
}

void saveHx(int32_t offset, float scale) {
  prefsBegin(false);
  prefs.putInt("hxOff", offset);
  prefs.putFloat("hxScale", scale);
  prefsEnd();
}

bool loadHx(int32_t& offset, float& scale) {
  prefsBegin(true);
  bool has = prefs.isKey("hxScale");
  offset = prefs.getInt("hxOff", 0);
  scale = prefs.getFloat("hxScale", 420.0f);
  prefsEnd();
  return has;
}

void saveGasBaseline(float g) {
  prefsBegin(false);
  prefs.putFloat("gasBase", g);
  prefsEnd();
}

float loadGasBaseline() {
  prefsBegin(true);
  float g = prefs.getFloat("gasBase", 0);
  prefsEnd();
  return g;
}

void saveImuRef(float x, float y, float z) {
  float v[3] = {x, y, z};
  prefsBegin(false);
  prefs.putBytes("imuRef", v, sizeof(v));
  prefsEnd();
}

bool loadImuRef(float& x, float& y, float& z) {
  float v[3];
  prefsBegin(true);
  bool ok = prefs.getBytesLength("imuRef") == sizeof(v) && prefs.getBytes("imuRef", v, sizeof(v)) == sizeof(v);
  prefsEnd();
  if (ok) { x = v[0]; y = v[1]; z = v[2]; }
  return ok;
}

static double r1(float v) { return round(v * 10.0) / 10.0; }
static double r2(float v) { return round(v * 100.0) / 100.0; }

void calToJson(const Cal& c, JsonObject o) {
  o["d_empty"] = r1(c.d_empty);
  o["d_full"] = r1(c.d_full);
  o["fill_th"] = r1(c.fill_th);
  o["temp_th"] = r1(c.temp_th);
  o["hum_th"] = r1(c.hum_th);
  o["gas_th"] = r1(c.gas_th);
  o["tilt_th"] = r1(c.tilt_th);
  o["weight_cap"] = r1(c.weight_cap);
  o["auto_lock"] = c.auto_lock;
  o["servo_closed"] = c.servo_closed;
  o["servo_open"] = c.servo_open;
  o["collect_pin"] = c.collect_pin;
  o["pir_hold_s"] = c.pir_hold_s;
  o["buzz_s"] = c.buzz_s;
  o["temp_off"] = r1(c.temp_off);
  o["hum_off"] = r1(c.hum_off);
  o["press_off"] = r1(c.press_off);
  o["buzz_on"] = c.buzz_on;
  o["buzz_vol"] = c.buzz_vol;
  o["buzz_ui"] = c.buzz_ui;
  o["buzz_lid"] = c.buzz_lid;
  o["buzz_repeat_s"] = c.buzz_repeat_s;
  o["quiet_on"] = c.quiet_on;
  o["quiet_from"] = c.quiet_from;
  o["quiet_to"] = c.quiet_to;
  o["lid_close_s"] = c.lid_close_s;
  o["tilt_boot"] = c.tilt_boot;
}

template <typename T>
static bool take(JsonObjectConst o, const char* k, T& dst, float lo, float hi) {
  JsonVariantConst v = o[k];
  if (v.isNull() || !(v.is<float>() || v.is<int>() || v.is<const char*>())) return false;
  float f = v.is<const char*>() ? atof(v.as<const char*>()) : v.as<float>();
  if (isnan(f)) return false;
  f = constrain(f, lo, hi);
  dst = (T)f;
  return true;
}

bool calFromJson(JsonObjectConst o, Cal& c) {
  bool any = false;
  any |= take(o, "d_empty", c.d_empty, 5, 200);
  any |= take(o, "d_full", c.d_full, 1, 100);
  any |= take(o, "fill_th", c.fill_th, 10, 100);
  any |= take(o, "temp_th", c.temp_th, 10, 85);
  any |= take(o, "hum_th", c.hum_th, 10, 100);
  any |= take(o, "gas_th", c.gas_th, 50, 500);
  any |= take(o, "tilt_th", c.tilt_th, 5, 90);
  any |= take(o, "weight_cap", c.weight_cap, 100, 50000);
  any |= take(o, "auto_lock", c.auto_lock, 0, 1);
  any |= take(o, "servo_closed", c.servo_closed, 0, 180);
  any |= take(o, "servo_open", c.servo_open, 0, 180);
  any |= take(o, "collect_pin", c.collect_pin, 0, 9999);
  any |= take(o, "pir_hold_s", c.pir_hold_s, 1, 120);
  any |= take(o, "buzz_s", c.buzz_s, 0, 60);
  any |= take(o, "temp_off", c.temp_off, -20, 20);
  any |= take(o, "hum_off", c.hum_off, -30, 30);
  any |= take(o, "press_off", c.press_off, -100, 100);
  any |= take(o, "buzz_on", c.buzz_on, 0, 1);
  any |= take(o, "buzz_vol", c.buzz_vol, 1, 3);
  any |= take(o, "buzz_ui", c.buzz_ui, 0, 1);
  any |= take(o, "buzz_lid", c.buzz_lid, 0, 1);
  any |= take(o, "buzz_repeat_s", c.buzz_repeat_s, 10, 300);
  any |= take(o, "quiet_on", c.quiet_on, 0, 1);
  any |= take(o, "quiet_from", c.quiet_from, 0, 1439);
  any |= take(o, "quiet_to", c.quiet_to, 0, 1439);
  any |= take(o, "lid_close_s", c.lid_close_s, 0, 30);
  any |= take(o, "tilt_boot", c.tilt_boot, 0, 1);
  if (c.d_full >= c.d_empty) c.d_full = c.d_empty - 1;
  return any;
}

String localIp() { return WiFi.isConnected() ? WiFi.localIP().toString() : String(""); }

void statusToJson(const BinState& s, const Cal& c, const Sim& sm, JsonObject o, bool withCal, bool forCloud) {
  const Reading& v = s.v;
  bool hc = s.okHC || (sm.mask & 1), hx = s.okHX || (sm.mask & 2), bme = s.okBME || (sm.mask & 12) == 12;
  o["id"] = BIN_ID;
  o["name"] = BIN_LABEL;
  if (hc) {
    o["fill"] = r1(v.fill);
    o["distance_cm"] = r1(v.distance_cm);
  }
  if (hx) o["weight_g"] = (long)lround(v.weight_g);
  if (bme || (sm.mask & 8)) {
    o["temp"] = r2(v.temp);
    o["temp_raw"] = r2(v.temp_raw);
    o["humidity"] = r1(v.humidity);
  }
  if (s.okBME) o["pressure"] = r1(v.pressure);
  if (s.okBME) o["gas_k"] = r1(v.gas_k);
  if (s.okBME && v.iaq_acc == 0 && !(sm.mask & 4)) o["iaq_warm_s"] = airWarmLeft();
  if (s.okBME || (sm.mask & 4)) {
    o["iaq"] = (long)lround(v.iaq);
    o["iaq_acc"] = v.iaq_acc;
  }
  o["tilt"] = r1(v.tilt);
  if (s.okIMU) {
    o["pitch"] = r1(s.pitch);
    o["roll"] = r1(s.roll);
  }
  o["motion"] = v.motion;
  o["lid"] = s.lidOpen ? "OPEN" : "CLOSED";
  o["status"] = STATUS_NAMES[s.status];
  // "status" is one word with a priority (calibrating, collecting, tamper, locked), so a
  // locked bin in tamper says TAMPER. These flags say every mode that is on, so the
  // dashboard and the app show the same lock and pickup state whatever outranks it.
  JsonObject m = o["modes"].to<JsonObject>();
  m["locked"] = s.locked;
  m["auto_locked"] = s.locked && s.autoLocked;
  m["collecting"] = s.collecting;
  m["calibrating"] = s.calibrating;
  m["tamper"] = s.tamperPhase != 0;
  o["sim_mask"] = sm.mask;
  if (s.tamperPhase) o["tamper_phase"] = s.tamperPhase == 1 ? "COUNTDOWN" : "LATCHED";
  JsonArray f = o["faults"].to<JsonArray>();
  if (!s.okHC) f.add("HC-SR04");
  if (!s.okHX) f.add("HX711");
  if (!s.okBME) f.add("BME680");
  if (!s.okIMU) f.add("BMI160");
  if (withCal) {
    JsonObject co = o["cal"].to<JsonObject>();
    calToJson(c, co);
    // The upright reference lives beside Cal (its own key in flash), so it is added here.
    co["tilt_ref_pitch"] = r1(s.refPitch);
    co["tilt_ref_roll"] = r1(s.refRoll);
    co["tilt_ref_saved"] = s.refSaved;
    // What each sensor is held at in simulation, so every dashboard and phone shows the same values.
    JsonObject so = o["sim"].to<JsonObject>();
    so["fill"] = r1(sm.fill);
    so["weight"] = r1(sm.weight);
    so["gas"] = r1(sm.gas);
    so["temp"] = r1(sm.temp);
    so["humidity"] = r1(sm.humidity);
    so["tamper"] = sm.tamper ? 1 : 0;
    so["motion"] = sm.motion ? 1 : 0;
    so["lid"] = sm.lid ? 1 : 0;
  }
  if (withCal) {
    JsonObject t = o["touch"].to<JsonObject>();
    t["swap"] = touchCal.swap;
    t["x0"] = touchCal.x0;
    t["x1"] = touchCal.x1;
    t["y0"] = touchCal.y0;
    t["y1"] = touchCal.y1;
    t["custom"] = touchCalCustom;
  }
  if (s.last.cmd[0]) {
    JsonObject lc = o["last_cmd"].to<JsonObject>();
    lc["cmd"] = s.last.cmd;
    lc["ts"] = s.last.ts;
    lc["ok"] = s.last.ok;
  }
  o["uptime_s"] = (uint32_t)(millis() / 1000);
  o["rssi"] = s.rssi;
  o["fw"] = FW_VERSION;
  if (forCloud) {
    o["local_ip"] = localIp();
    o["ts"][".sv"] = "timestamp";
  }
}

String statusJson(bool withCal, bool forCloud) {
  lockState();
  BinState s = st;
  Cal c = cal;
  Sim sm = sim;
  unlockState();
  JsonDocument doc;
  statusToJson(s, c, sm, doc.to<JsonObject>(), withCal, forCloud);
  String out;
  serializeJson(doc, out);
  return out;
}

static const char* const KNOWN[] = {"LOCK", "UNLOCK", "CLEAR_TAMPER", "COLLECT", "COLLECT_DONE", "CALIBRATE_START",
                                    "CALIBRATE_END", "TARE", "CAL_WEIGHT", "RESET_GAS", "BUZZ_TEST",
                                    "TOUCH_CAL", "TOUCH_CAL_RESET"};

bool validateCommand(const char* cmd, const BinState& s, String& why) {
  bool known = false;
  for (const char* k : KNOWN)
    if (!strcmp(k, cmd)) known = true;
  if (!known) { why = "Unknown command"; return false; }
  if (!strcmp(cmd, "COLLECT")) {
    if (s.calibrating) { why = "Calibrating"; return false; }
    if (s.tamperPhase) { why = "TAMPER active"; return false; }
  }
  if (!strcmp(cmd, "LOCK") && s.collecting) { why = "Collection in progress"; return false; }
  if (!strcmp(cmd, "CALIBRATE_START") && s.collecting) { why = "Collection in progress"; return false; }
  if ((!strcmp(cmd, "TARE") || !strcmp(cmd, "CAL_WEIGHT")) && !s.okHX) { why = "Load cell not answering"; return false; }
  if (!strcmp(cmd, "RESET_GAS") && !s.okBME) { why = "BME680 not answering"; return false; }
  return true;
}

bool sendCommand(const char* cmd, uint8_t src, float grams, const char* sound, const char* ref) {
  Command c{};
  strlcpy(c.cmd, cmd, sizeof(c.cmd));
  c.grams = grams;
  c.src = src;
  if (sound) strlcpy(c.sound, sound, sizeof(c.sound));
  if (ref) strlcpy(c.ref, ref, sizeof(c.ref));
  if (xQueueSend(cmdQueue, &c, pdMS_TO_TICKS(50)) != pdTRUE) return false;
  if (hControl) xTaskNotify(hControl, EV_CMD, eSetBits);
  return true;
}

void raiseAlert(const char* kind, const char* sev, const char* title, const char* meta) {
  AlertEv a{};
  strlcpy(a.kind, kind, sizeof(a.kind));
  strlcpy(a.sev, sev, sizeof(a.sev));
  strlcpy(a.title, title, sizeof(a.title));
  strlcpy(a.meta, meta, sizeof(a.meta));
  xQueueSend(alertQueue, &a, 0);
  if (hCloud) xTaskNotify(hCloud, EV_ALERT, eSetBits);
}

bool clockSet() { return time(nullptr) > 1700000000; }

int localMinuteOfDay() {
  if (!clockSet()) return -1;
  return (int)(((uint64_t)time(nullptr) + 8 * 3600ULL) / 60ULL % 1440ULL);
}

void monthKeyUtc(char* out, size_t n) {
  out[0] = 0;
  if (!clockSet()) return;
  int64_t z = (int64_t)(time(nullptr) / 86400) + 719468;
  int64_t era = z / 146097;
  int64_t doe = z - era * 146097;
  int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  int64_t mp = (5 * doy + 2) / 153;
  int m = (int)(mp < 10 ? mp + 3 : mp - 9);
  int y = (int)(yoe + era * 400 + (m <= 2));
  snprintf(out, n, "%04d-%02d", y, m);
}

uint64_t nowMs() {
  if (!clockSet()) return 0;
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  return (uint64_t)tv.tv_sec * 1000ULL + tv.tv_usec / 1000;
}
