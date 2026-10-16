#include "net.h"
#include "state.h"
#include "sensors.h"
#include "buzzer.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ESPAsyncWebServer.h>
#include <AsyncJson.h>
#include <esp_sntp.h>

#if __has_include("dashboard_gz.h")
#include "dashboard_gz.h"
#define HAVE_DASHBOARD 1
#else
#define HAVE_DASHBOARD 0
#endif

static AsyncWebServer server(80);
static AsyncEventSource events("/api/events");
static bool everConnected = false;

static void onWifiEvent(arduino_event_id_t event, arduino_event_info_t) {
  if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
    lockState();
    st.wifi = true;
    st.rssi = WiFi.RSSI();
    unlockState();
    MDNS.end();
    if (MDNS.begin(BIN_HOST)) MDNS.addService("http", "tcp", 80);
    if (!esp_sntp_enabled()) {
      esp_sntp_setoperatingmode(ESP_SNTP_OPMODE_POLL);
      esp_sntp_setservername(0, "pool.ntp.org");
      esp_sntp_setservername(1, "time.google.com");
      esp_sntp_init();
    }
    buzz(T_WIFI_UP);
    everConnected = true;
    Serial.printf("[wifi] %s  http://%s.local\n", WiFi.localIP().toString().c_str(), BIN_HOST);
    publish(EV_WIFI);
  } else if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    bool was;
    lockState();
    was = st.wifi;
    st.wifi = false;
    unlockState();
    if (was) {
      buzz(T_WIFI_LOST);
      Serial.println("[wifi] lost");
      publish(EV_WIFI);
    }
  }
}

void netInit() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(BIN_HOST);
  WiFi.setAutoReconnect(true);
  WiFi.onEvent(onWifiEvent);
}

void wifiConnect() {
  if (net.ssid.length() == 0 || net.ssid == "your-wifi-name") {
    Serial.println("[wifi] no network set; use WI-FI SETUP on the touchscreen");
    return;
  }
  WiFi.begin(net.ssid.c_str(), net.pass.c_str());
}

static void sendJson(AsyncWebServerRequest* r, int code, const String& body) {
  AsyncWebServerResponse* res = r->beginResponse(code, "application/json", body);
  res->addHeader("Cache-Control", "no-store");
  r->send(res);
}

static const char* expectStatus(const char* cmd, const BinState& s) {
  if (!strcmp(cmd, "LOCK")) return "LOCKED";
  if (!strcmp(cmd, "COLLECT")) return "COLLECTING";
  if (!strcmp(cmd, "CALIBRATE_START")) return "CALIBRATING";
  if (!strcmp(cmd, "UNLOCK") || !strcmp(cmd, "CLEAR_TAMPER") || !strcmp(cmd, "COLLECT_DONE") || !strcmp(cmd, "CALIBRATE_END"))
    return s.tamperPhase && strcmp(cmd, "CLEAR_TAMPER") ? "TAMPER" : (s.locked && strcmp(cmd, "UNLOCK") && !s.autoLocked) ? "LOCKED" : "OK";
  return STATUS_NAMES[s.status];
}

static void handleCommand(AsyncWebServerRequest* r, JsonVariant& j) {
  const char* cmd = j["cmd"] | "";
  BinState s = snapshot();
  String why;
  JsonDocument out;
  out["cmd"] = cmd;
  if (!validateCommand(cmd, s, why)) {
    out["ok"] = false;
    out["why"] = why;
    String b;
    serializeJson(out, b);
    sendJson(r, why == "Unknown command" ? 400 : 409, b);
    return;
  }
  if (!sendCommand(cmd, SRC_LOCAL, j["grams"] | 0.0f, j["sound"] | (const char*)nullptr)) {
    sendJson(r, 503, "{\"ok\":false,\"why\":\"Busy, try again\"}");
    return;
  }
  out["ok"] = true;
  out["status"] = expectStatus(cmd, s);
  String b;
  serializeJson(out, b);
  sendJson(r, 200, b);
}

static void handleCalibrate(AsyncWebServerRequest* r, JsonVariant& j) {
  if (!j.is<JsonObject>()) { sendJson(r, 400, "{\"ok\":false,\"why\":\"Send a JSON object\"}"); return; }
  JsonObjectConst o = j.as<JsonObjectConst>();
  lockState();
  Cal c = cal;
  bool any = calFromJson(o, c);
  if (any) {
    cal = c;
    if (!o["d_empty"].isNull()) st.calSteps |= 2;
    if (!o["d_full"].isNull()) st.calSteps |= 4;
    st.calVersion++;
    recomputeView();
  }
  bool calibrating = st.calibrating;
  unlockState();
  if (!any) { sendJson(r, 400, "{\"ok\":false,\"why\":\"No known calibration keys\"}"); return; }
  saveCal();
  buzz(calibrating ? T_CAL_STEP : T_SAVED);
  publish(EV_CAL);
  sendJson(r, 200, "{\"ok\":true}");
}

static void handleSimulate(AsyncWebServerRequest* r, JsonVariant& j) {
  lockState();
  sim.mask = (uint8_t)(j["mask"] | 0) & 0x7F;
  JsonObjectConst v = j["values"].as<JsonObjectConst>();
  if (!v.isNull()) {
    sim.fill = v["fill"] | sim.fill;
    sim.weight = v["weight"] | sim.weight;
    sim.gas = v["gas"] | sim.gas;
    sim.temp = v["temp"] | sim.temp;
    sim.humidity = v["humidity"] | sim.humidity;
    if (!v["tamper"].isNull()) sim.tamper = v["tamper"].as<bool>();
    if (!v["motion"].isNull()) sim.motion = v["motion"].as<bool>();
    if (!v["lid"].isNull()) sim.lid = v["lid"].as<bool>() || (v["lid"].is<const char*>() && !strcmp(v["lid"], "OPEN"));
  }
  recomputeView();
  unlockState();
  if (hControl) xTaskNotify(hControl, EV_STATE, eSetBits);
  publish();
  sendJson(r, 200, "{\"ok\":true}");
}

static void handleCloud(AsyncWebServerRequest* r, JsonVariant& j) {
  String url = j["url"] | "", key = j["key"] | "", email = j["email"] | "", pass = j["pass"] | "";
  url.trim();
  while (url.endsWith("/")) url.remove(url.length() - 1);
  if (url.length() && !url.startsWith("https://") && !url.startsWith("http://")) {
    sendJson(r, 400, "{\"ok\":false,\"why\":\"The database URL starts with https://\"}");
    return;
  }
  net.fbUrl = url;
  net.fbKey = key;
  net.fbEmail = email;
  net.fbPass = pass;
  saveNet();
  if (hCloud) xTaskNotify(hCloud, EV_WIFI, eSetBits);
  sendJson(r, 200, "{\"ok\":true}");
}

void startWebServer() {
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Headers", "Content-Type, X-SB-Op");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Private-Network", "true");

  server.on("/api/status", HTTP_GET, [](AsyncWebServerRequest* r) { sendJson(r, 200, statusJson(true, false)); });

  auto add = [](const char* uri, ArJsonRequestHandlerFunction fn) {
    AsyncCallbackJsonWebHandler* h = new AsyncCallbackJsonWebHandler(uri, fn);
    h->setMethod(HTTP_POST);
    h->setMaxContentLength(1024);
    server.addHandler(h);
  };
  add("/api/command", handleCommand);
  add("/api/calibrate", handleCalibrate);
  add("/api/simulate", handleSimulate);
  add("/api/cloud", handleCloud);

  events.onConnect([](AsyncEventSourceClient* c) { c->send(statusJson(true, false).c_str(), "status", millis(), 3000); });
  server.addHandler(&events);

  server.on("/", HTTP_GET, [](AsyncWebServerRequest* r) {
#if HAVE_DASHBOARD
    AsyncWebServerResponse* res = r->beginResponse(200, "text/html", DASHBOARD_GZ, DASHBOARD_GZ_LEN);
    res->addHeader("Content-Encoding", "gzip");
    r->send(res);
#else
    r->send(200, "text/html",
            "<!doctype html><meta name=viewport content='width=device-width'><title>" BIN_LABEL "</title>"
            "<body style='font-family:sans-serif;padding:24px'><h1>" BIN_LABEL "</h1>"
            "<p>The dashboard is not built into this firmware. Open <a href='/api/status'>/api/status</a>, or run "
            "tools/embed_dashboard.py and upload again.</p>");
#endif
  });

  server.onNotFound([](AsyncWebServerRequest* r) {
    if (r->method() == HTTP_OPTIONS) r->send(204);
    else sendJson(r, 404, "{\"ok\":false,\"why\":\"Not found\"}");
  });
  server.begin();
}

void pushTask(void*) {
  uint32_t lastSent = 0, lastPush = 0, lastPing = 0, lastRssi = 0;
  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(250));
    uint32_t now = millis();
    if (now - lastRssi > 5000 && WiFi.isConnected()) {
      lastRssi = now;
      lockState();
      st.rssi = WiFi.RSSI();
      unlockState();
    }
    if (!events.count()) continue;
    lockState();
    uint32_t v = st.version;
    unlockState();
    if (v != lastSent && now - lastPush >= 250) {
      events.send(statusJson(true, false).c_str(), "status", now);
      lastSent = v;
      lastPush = now;
    }
    if (now - lastPing >= 10000) {
      lastPing = now;
      events.send("1", "ping", now);
    }
  }
}
