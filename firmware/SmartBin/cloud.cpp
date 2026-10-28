#include "cloud.h"
#include "state.h"
#include "buzzer.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <vector>
#include <algorithm>

struct Svc {
  WiFiClientSecure tls;
  WiFiClient plain;
  HTTPClient http;
  uint32_t retryAt = 0;
  uint8_t fails = 0;
};

static Svc fb, ts, auth, tb;
static String idToken;
static uint32_t tokenUntil = 0;
static bool fbOk = false;
static int watchers = 0;
static uint64_t monthBytes = 0;
static String monthKey;
static float usageFraction = 0;

bool cloudFirebaseOn() { return net.fbUrl.length() && net.fbKey.length() && net.fbEmail.length() && net.fbPass.length(); }

static String lastFail;

static int request(Svc& s, const String& url, const char* method, const String& body, String* out,
                   const char* ctype = "application/json") {
  Client& c = url.startsWith("https://") ? (Client&)s.tls : (Client&)s.plain;
  s.http.setReuse(true);
  s.http.setTimeout(8000);
  s.http.setConnectTimeout(6000);
  if (!s.http.begin((NetworkClient&)c, url)) return -1;
  if (body.length()) s.http.addHeader("Content-Type", ctype);
  int code = s.http.sendRequest(method, (uint8_t*)body.c_str(), body.length());
  if (code <= 0 && url.startsWith("https://")) {
    char tlsMsg[96] = "";
    int tlsCode = s.tls.lastError(tlsMsg, sizeof(tlsMsg));
    String host = url.substring(8, url.indexOf('/', 8));
    IPAddress ip;
    bool dns = WiFi.hostByName(host.c_str(), ip);
    Serial.printf("[https] connect to %s failed: %s, tls %d %s, dns %s, free heap %u, largest block %u\n", host.c_str(),
                  HTTPClient::errorToString(code).c_str(), tlsCode, tlsMsg, dns ? "ok" : "FAILED", (unsigned)ESP.getFreeHeap(),
                  (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
  }
  String got = code > 0 ? s.http.getString() : String();
  if (code >= 400 || code <= 0) lastFail = got.substring(0, 100);
  if (out) *out = got;
  s.http.end();
  return code;
}

static void backoff(Svc& s) {
  static const uint16_t steps[] = {5, 15, 30, 60};
  s.retryAt = millis() + steps[min<uint8_t>(s.fails, 3)] * 1000UL;
  if (s.fails < 10) s.fails++;
  s.tls.stop();
  s.plain.stop();
}

static bool due(const Svc& s) { return (int32_t)(millis() - s.retryAt) >= 0; }

static void setFbOk(bool ok) {
  if (ok == fbOk) return;
  fbOk = ok;
  lockState();
  st.cloudOk = ok;
  unlockState();
  buzz(ok ? T_CLOUD_UP : T_CLOUD_DOWN);
  Serial.printf("[firebase] %s (free heap %u)\n", ok ? "reachable" : "cannot be reached", (unsigned)ESP.getFreeHeap());
  publish();
}

static String enc(const String& s) {
  String o;
  const char* hex = "0123456789ABCDEF";
  for (size_t i = 0; i < s.length(); i++) {
    char ch = s[i];
    if (isalnum((unsigned char)ch) || ch == '-' || ch == '_' || ch == '.' || ch == '~') o += ch;
    else { o += '%'; o += hex[(ch >> 4) & 15]; o += hex[ch & 15]; }
  }
  return o;
}

static void closeIdleTls();

static bool signIn() {
  closeIdleTls();
  String url = net.fbUrl.startsWith("http://")
                   ? net.fbUrl + "/__auth/signInWithPassword?key=" + enc(net.fbKey)
                   : "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + enc(net.fbKey);
  JsonDocument d;
  d["email"] = net.fbEmail;
  d["password"] = net.fbPass;
  d["returnSecureToken"] = true;
  String body, resp;
  serializeJson(d, body);
  auth.http.setReuse(false);
  Client& c = url.startsWith("https://") ? (Client&)auth.tls : (Client&)auth.plain;
  auth.http.setTimeout(10000);
  if (!auth.http.begin((NetworkClient&)c, url)) return false;
  auth.http.addHeader("Content-Type", "application/json");
  int code = auth.http.POST(body);
  char tlsMsg[96] = "";
  int tlsCode = 0;
  if (code <= 0 && url.startsWith("https://")) tlsCode = auth.tls.lastError(tlsMsg, sizeof(tlsMsg));
  if (code > 0) resp = auth.http.getString();
  auth.http.end();
  auth.tls.stop();
  if (code != 200) {
    Serial.printf("[firebase] sign-in failed (%d) %s\n", code, resp.substring(0, 120).c_str());
    if (code <= 0) {
      IPAddress ip;
      bool dns = WiFi.hostByName("identitytoolkit.googleapis.com", ip);
      Serial.printf("[firebase] %s, dns %s %s, wifi %d dBm, free heap %u, largest block %u\n", HTTPClient::errorToString(code).c_str(),
                    dns ? "ok" : "FAILED", dns ? ip.toString().c_str() : "", WiFi.RSSI(), (unsigned)ESP.getFreeHeap(),
                    (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
      Serial.printf("[firebase] tls error %d: %s\n", tlsCode, tlsMsg);
    }
    return false;
  }
  JsonDocument r;
  if (deserializeJson(r, resp)) return false;
  idToken = r["idToken"] | "";
  Serial.printf("[firebase] signed in as %s\n", net.fbEmail.c_str());
  uint32_t secs = atoi(r["expiresIn"] | "3600");
  tokenUntil = millis() + (secs > 400 ? secs - 300 : secs) * 1000UL;
  return idToken.length() > 0;
}

static String fbUrl(const String& path, const String& query = "") {
  return net.fbUrl + "/" + path + ".json?auth=" + enc(idToken) + query;
}

static int fbCall(const char* method, const String& path, const String& body, String* out, const String& query = "",
                  bool count = true) {
  if (!idToken.length() || (int32_t)(millis() - tokenUntil) >= 0) {
    if (!due(fb)) return -1;
    if (!signIn()) {
      backoff(fb);
      setFbOk(false);
      return -1;
    }
  }
  int code = request(fb, fbUrl(path, query), method, body, out);
  if (code == 401 && lastFail.indexOf("Permission denied") < 0) {
    idToken = "";
    if (!signIn()) {
      backoff(fb);
      setFbOk(false);
      return -1;
    }
    code = request(fb, fbUrl(path, query), method, body, out);
  }
  if (code <= 0 || code >= 400) Serial.printf("[firebase] %s %s -> %d %s\n", method, path.c_str(), code, lastFail.c_str());
  if (code <= 0 || code >= 500) {
    backoff(fb);
    setFbOk(false);
    return code;
  }
  fb.fails = 0;
  static bool refusedShown = false;
  if ((code == 401 || code == 403) && !refusedShown && (refusedShown = true)) Serial.printf("[firebase] the rules refused this account (%s); a bin account's email must start with \"bin\"\n", net.fbEmail.c_str());
  setFbOk(code < 400);
  if (count && (!strcmp(method, "PUT") || !strcmp(method, "PATCH") || !strcmp(method, "POST")))
    monthBytes += (uint64_t)body.length() * max(1, watchers);
  return code;
}

static String currentMonth() {
  char b[12];
  monthKeyUtc(b, sizeof(b));
  return b;
}

static void loadUsage() {
  String out;
  if (fbCall("GET", String("sb/usage/by_bin/") + BIN_ID, "", &out, "", false) != 200) return;
  JsonDocument d;
  if (deserializeJson(d, out) || !d.is<JsonObject>()) return;
  String m = d["month"] | "";
  monthKey = currentMonth();
  if (m == monthKey) monthBytes = d["bytes"] | (uint64_t)0;
}

static void writeUsage() {
  String m = currentMonth();
  if (!m.length()) return;
  if (m != monthKey) { monthKey = m; monthBytes = 0; }
  JsonDocument me;
  me["month"] = m;
  me["bytes"] = monthBytes;
  String b;
  serializeJson(me, b);
  fbCall("PUT", String("sb/usage/by_bin/") + BIN_ID, b, nullptr, "", false);
  String out;
  if (fbCall("GET", "sb/usage/by_bin", "", &out, "", false) != 200) return;
  JsonDocument all;
  if (deserializeJson(all, out)) return;
  uint64_t total = 0;
  for (JsonPair p : all.as<JsonObject>())
    if (m == (p.value()["month"] | "")) total += p.value()["bytes"] | (uint64_t)0;
  usageFraction = (float)((double)total / (double)FB_MONTH_LIMIT);
  JsonDocument u;
  u["month"] = m;
  u["bytes_month"] = total;
  u["down_bytes"] = total;
  u["limit_bytes"] = FB_MONTH_LIMIT;
  u["fraction"] = round(usageFraction * 10000.0) / 10000.0;
  u["level"] = usageFraction >= 1 ? "bad" : usageFraction >= 0.9 ? "near" : usageFraction >= 0.7 ? "warn" : "ok";
  u["estimate"] = true;
  b = "";
  serializeJson(u, b);
  fbCall("PATCH", "sb/usage", b, nullptr, "", false);
}

static void readWatchers() {
  String out;
  int code = -1;
  if (clockSet()) {
    uint64_t since = nowMs() - 30000ULL;
    code = fbCall("GET", "sb/watchers", "", &out, "&orderBy=%22ts%22&startAt=" + String((unsigned long long)since), false);
  }
  if (code != 200) code = fbCall("GET", "sb/watchers", "", &out, "", false);
  if (code != 200) return;
  JsonDocument d;
  if (deserializeJson(d, out) || !d.is<JsonObject>()) { watchers = 0; return; }
  int n = 0;
  uint64_t now = nowMs();
  for (JsonPair p : d.as<JsonObject>()) {
    uint64_t t = p.value()["ts"] | (uint64_t)0;
    if (!now || (t && now - t <= 30000ULL)) n++;
  }
  watchers = n;
}

static void pollCommands() {
  String out;
  if (fbCall("GET", String("sb/commands/") + BIN_ID, "", &out, "", false) != 200) return;
  if (out == "null" || out.length() < 3) return;
  JsonDocument d;
  if (deserializeJson(d, out) || !d.is<JsonObject>()) return;
  uint64_t now = nowMs();
  for (JsonPair p : d.as<JsonObject>()) {
    String key = p.key().c_str();
    const char* cmd = p.value()["cmd"] | "";
    uint64_t t = p.value()["ts"] | (uint64_t)0;
    fbCall("DELETE", String("sb/commands/") + BIN_ID + "/" + key, "", nullptr, "", false);
    bool stale = now && t && now > t && now - t > CMD_MAX_AGE_MS;
    bool allowed = !strcmp(cmd, "LOCK") || !strcmp(cmd, "UNLOCK") || !strcmp(cmd, "COLLECT");
    if (stale || !allowed) {
      JsonDocument r;
      r["ok"] = false;
      r["cmd"] = cmd;
      r["why"] = stale ? "Too old, ignored" : "Needs the bin's Wi-Fi";
      r["ts"][".sv"] = "timestamp";
      String b;
      serializeJson(r, b);
      fbCall("PUT", "sb/results/" + key, b, nullptr);
      continue;
    }
    Serial.printf("[firebase] command %s\n", cmd);
    if (!sendCommand(cmd, SRC_FIREBASE, 0, nullptr, key.c_str())) {
      JsonDocument r;
      r["ok"] = false;
      r["cmd"] = cmd;
      r["why"] = "Busy";
      String b;
      serializeJson(r, b);
      fbCall("PUT", "sb/results/" + key, b, nullptr);
    }
  }
}

static void flushResults() {
  CmdResult r;
  while (xQueueReceive(resultQueue, &r, 0) == pdTRUE) {
    if (!r.ref[0]) continue;
    JsonDocument d;
    d["ok"] = r.ok;
    d["cmd"] = r.cmd;
    if (r.why[0]) d["why"] = r.why;
    d["ts"][".sv"] = "timestamp";
    String b;
    serializeJson(d, b);
    fbCall("PUT", String("sb/results/") + r.ref, b, nullptr);
  }
}

static void flushAlerts(bool fbOn) {
  AlertEv a;
  while (xQueueReceive(alertQueue, &a, 0) == pdTRUE) {
    Serial.printf("[alert] %s: %s (%s)\n", a.title, a.meta, a.sev);
    if (!fbOn) continue;
    JsonDocument d;
    d["kind"] = a.kind;
    d["sev"] = a.sev;
    d["title"] = a.title;
    d["meta"] = a.meta;
    d["ts"][".sv"] = "timestamp";
    String b;
    serializeJson(d, b);
    fbCall("POST", String("sb/alerts/") + BIN_ID, b, nullptr);
  }
}

static void trimAlerts() {
  String out;
  if (fbCall("GET", String("sb/alerts/") + BIN_ID, "", &out, "&shallow=true", false) != 200) return;
  JsonDocument d;
  if (deserializeJson(d, out) || !d.is<JsonObject>()) return;
  JsonObject o = d.as<JsonObject>();
  int n = o.size();
  if (n <= 50) return;
  std::vector<String> keys;
  for (JsonPair p : o) keys.push_back(p.key().c_str());
  std::sort(keys.begin(), keys.end());
  for (int i = 0; i < n - 50; i++) fbCall("DELETE", String("sb/alerts/") + BIN_ID + "/" + keys[i], "", nullptr, "", false);
}

struct Sent {
  float fill = -100, weight = -1000, temp = -100, hum = -100, iaq = -100, tilt = -100;
  uint8_t status = 255, tamper = 255, acc = 255;
  bool lid = false, motion = false, full = false, env = false;
  uint8_t faults = 255;
  uint8_t modes = 255;
};

static uint8_t modeBits(const BinState& s) { return s.locked | (s.collecting << 1) | (s.calibrating << 2) | (s.autoLocked << 3); }

static uint8_t faultBits(const BinState& s) { return (!s.okHC) | (!s.okHX << 1) | (!s.okBME << 2) | (!s.okIMU << 3); }

static bool importantChange(const Sent& a, const BinState& s) {
  return a.status != s.status || a.lid != s.lidOpen || a.tamper != s.tamperPhase || a.full != s.alarmFull ||
         a.env != s.alarmEnv || a.faults != faultBits(s) || a.modes != modeBits(s);
}

static bool minorChange(const Sent& a, const BinState& s) {
  const Reading& v = s.v;
  return fabsf(a.fill - v.fill) >= 1 || fabsf(a.weight - v.weight_g) >= 20 || fabsf(a.temp - v.temp) >= 0.2f ||
         fabsf(a.hum - v.humidity) >= 1 || fabsf(a.iaq - v.iaq) >= 5 || fabsf(a.tilt - v.tilt) >= 1 ||
         a.motion != v.motion || a.acc != v.iaq_acc;
}

static void remember(Sent& a, const BinState& s) {
  a.fill = s.v.fill; a.weight = s.v.weight_g; a.temp = s.v.temp; a.hum = s.v.humidity; a.iaq = s.v.iaq;
  a.tilt = s.v.tilt; a.status = s.status; a.tamper = s.tamperPhase; a.acc = s.v.iaq_acc; a.lid = s.lidOpen;
  a.motion = s.v.motion; a.full = s.alarmFull; a.env = s.alarmEnv; a.faults = faultBits(s); a.modes = modeBits(s);
}

static void addField(String& url, int n, float v, int dec) {
  if (isnan(v) || isinf(v)) return;
  url += "&field" + String(n) + "=" + String(v, dec);
}

static bool tsWrite(const BinState& s) {
  const Reading& v = s.v;
  int flags = (v.motion ? 1 : 0) + (s.lidOpen ? 2 : 0) + 4 * s.status;
  String url = net.tsServer + "/update?api_key=" + enc(net.tsWriteKey);
  addField(url, 1, v.fill, 1);
  addField(url, 2, v.weight_g, 0);
  addField(url, 3, v.temp, 2);
  addField(url, 4, v.humidity, 1);
  addField(url, 5, v.iaq, 0);
  addField(url, 6, v.pressure, 1);
  addField(url, 7, v.tilt, 1);
  url += "&field8=" + String(flags) + "&status=" + enc(localIp());
  if (url.startsWith("https://")) {
    for (Svc* x : {&fb, &tb}) {
      x->http.end();
      x->tls.stop();
    }
  }
  String out;
  int code = request(ts, url, "GET", "", &out);
  out.trim();
  bool ok = code == 200 && out != "0";
  if (code <= 0 || code >= 500) backoff(ts);
  else ts.fails = 0;
  lockState();
  st.tsOk = ok;
  unlockState();
  if (!ok) {
    String shown = url;
    shown.replace(enc(net.tsWriteKey), "<key>");
    Serial.printf("[thingspeak] write failed (%d) %s\n  %s\n", code, out.substring(0, 60).c_str(), shown.c_str());
  }
  return ok;
}

static void closeIdleTls() {
  for (Svc* x : {&fb, &ts, &tb}) {
    x->http.end();
    x->tls.stop();
    x->plain.stop();
  }
}

static void talkBack() {
  String url = net.tsServer + "/talkbacks/" + enc(net.tbId) + "/commands/execute.json";
  String out;
  int code = request(tb, url, "POST", "api_key=" + enc(net.tbKey), &out, "application/x-www-form-urlencoded");
  if (code <= 0 || code >= 500) { backoff(tb); return; }
  tb.fails = 0;
  if (code != 200 || out.length() < 3) return;
  JsonDocument d;
  if (deserializeJson(d, out)) return;
  String cmd = d["command_string"] | "";
  cmd.trim();
  cmd.toUpperCase();
  if (cmd == "LOCK" || cmd == "UNLOCK" || cmd == "COLLECT") {
    Serial.printf("[talkback] command %s\n", cmd.c_str());
    sendCommand(cmd.c_str(), SRC_TALKBACK);
  }
}

void cloudTask(void*) {
  for (Svc* s : {&fb, &ts, &auth, &tb}) {
    s->tls.setInsecure();
    s->tls.setHandshakeTimeout(12);
  }
  Sent fbSent, tsSent;
  uint32_t lastStatus = 0, lastCmd = 0, lastWatch = 0, lastUsage = 0, lastTrim = 0, lastTs = 0, lastTb = 0, lastCalVer = 0;
  bool usageLoaded = false, wasConnected = false;
  bool calSent = false;

  bool stackShown = false;

  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(500));
    if (!stackShown && millis() > 45000) {
      stackShown = true;
      Serial.printf("[stack] bytes never used: ui %u, cloud %u, push %u, control %u, sensor %u, free heap %u\n",
                    (unsigned)uxTaskGetStackHighWaterMark(hUi), (unsigned)uxTaskGetStackHighWaterMark(hCloud),
                    (unsigned)uxTaskGetStackHighWaterMark(hPush), (unsigned)uxTaskGetStackHighWaterMark(hControl),
                    (unsigned)uxTaskGetStackHighWaterMark(hSensor), (unsigned)ESP.getFreeHeap());
    }
    if (ev & EV_WIFI) {
      idToken = "";
      fb.retryAt = 0;
      fb.fails = 0;
      calSent = false;
    }
    BinState s = snapshot();
    bool online = WiFi.isConnected() && !s.btOn;
    bool fbOn = cloudFirebaseOn();
    if (!online) {
      if (wasConnected) {
        for (Svc* x : {&fb, &ts, &tb}) { x->tls.stop(); x->plain.stop(); }
      }
      wasConnected = false;
      AlertEv a;
      while (xQueueReceive(alertQueue, &a, 0) == pdTRUE) Serial.printf("[alert] %s: %s\n", a.title, a.meta);
      continue;
    }
    wasConnected = true;
    uint32_t now = millis();

    if (fbOn && due(fb)) {
      if (!usageLoaded && clockSet()) {
        usageLoaded = true;
        loadUsage();
      }
      flushResults();
      flushAlerts(true);

      if (now - lastWatch >= FB_WATCHERS_MS || lastWatch == 0) {
        lastWatch = now;
        readWatchers();
      }
      bool watched = watchers > 0 || !FB_REQUIRE_WATCHERS;
      uint32_t every = !watched ? FB_IDLE_MS : usageFraction >= 0.9f ? 15000 : FB_WATCHED_MS;
      if (usageFraction >= 1.0f) every = FB_IDLE_MS;
      bool imp = importantChange(fbSent, s);
      bool minor = minorChange(fbSent, s);
      if (imp || (minor && now - lastStatus >= every) || now - lastStatus >= FB_IDLE_MS || lastStatus == 0) {
        String body = statusJson(false, true);
        if (fbCall("PUT", String("sb/bins/") + BIN_ID + "/status", body, nullptr) == 200) {
          remember(fbSent, s);
          lastStatus = now;
        }
      }
      if (!calSent || s.calVersion != lastCalVer) {
        JsonDocument d;
        JsonObject co = d.to<JsonObject>();
        calToJson(calSnapshot(), co);
        co["tilt_ref_pitch"] = roundf(s.refPitch * 10) / 10;
        co["tilt_ref_roll"] = roundf(s.refRoll * 10) / 10;
        String b;
        serializeJson(d, b);
        if (fbCall("PUT", String("sb/bins/") + BIN_ID + "/cal", b, nullptr) == 200) {
          calSent = true;
          lastCalVer = s.calVersion;
        }
      }
      if (now - lastCmd >= (watched ? FB_CMD_WATCHED_MS : FB_CMD_IDLE_MS)) {
        lastCmd = now;
        pollCommands();
      }
      if (now - lastUsage >= FB_USAGE_MS || lastUsage == 0) {
        lastUsage = now;
        writeUsage();
      }
      if (now - lastTrim >= 600000 || lastTrim == 0) {
        lastTrim = now;
        trimAlerts();
      }
    } else if (!fbOn) {
      flushAlerts(false);
      CmdResult r;
      while (xQueueReceive(resultQueue, &r, 0) == pdTRUE) {}
    }

    if (net.tsWriteKey.length() && due(ts)) {
      bool alertEdge = s.alarmFull != tsSent.full || s.tamperPhase != tsSent.tamper || s.status != tsSent.status;
      uint32_t since = now - lastTs;
      bool write = lastTs == 0 || (alertEdge && since >= TS_MIN_GAP_MS) ||
                   (since >= TS_WRITE_MS && (minorChange(tsSent, s) || importantChange(tsSent, s))) || since >= TS_HEARTBEAT_MS;
      if (write) {
        lastTs = now;
        if (tsWrite(s)) remember(tsSent, s);
      }
    }

    if (net.tbId.length() && net.tbKey.length() && due(tb)) {
      uint32_t every = (fbOn && watchers == 0 && FB_REQUIRE_WATCHERS) ? TB_IDLE_MS : TB_WATCHED_MS;
      if (now - lastTb >= every) {
        lastTb = now;
        talkBack();
      }
    }
  }
}
