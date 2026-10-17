#include "ble_setup.h"
#include "state.h"
#include "buzzer.h"
#include <WiFi.h>
#include <NimBLEDevice.h>

static SemaphoreHandle_t vLock;
static BtView view;
static volatile bool running = false, stopReq = false, stopRemote = false, joinReq = false, retryReq = false;
static String newSsid, newPass;
static uint8_t wrongCodes = 0;
static volatile bool codeShown = false;
static NimBLEServer* server = nullptr;
static NimBLECharacteristic *chStat = nullptr, *chScan = nullptr;
static uint16_t connHandle = 0xFFFF;
static TaskHandle_t hBle = nullptr;

static void setPhase(BtPhase p) {
  xSemaphoreTake(vLock, portMAX_DELAY);
  view.phase = p;
  view.phaseMs = millis();
  xSemaphoreGive(vLock);
  if (hUi) xTaskNotify(hUi, EV_BT, eSetBits);
}

BtView bleView() {
  if (!vLock) { BtView v{}; return v; }
  xSemaphoreTake(vLock, portMAX_DELAY);
  BtView v = view;
  xSemaphoreGive(vLock);
  return v;
}

static void notifyStat(const String& json) {
  if (!chStat) return;
  chStat->setValue(json);
  chStat->notify();
}

class ServerCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override {
    connHandle = info.getConnHandle();
    codeShown = false;
    xSemaphoreTake(vLock, portMAX_DELAY);
    view.offAtMs = millis() + BT_IDLE_OFF_MS;
    xSemaphoreGive(vLock);
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    connHandle = 0xFFFF;
    BtPhase p = bleView().phase;
    if (p == BT_CODE || p == BT_PAIRED) setPhase(BT_WAIT);
    if (p != BT_LOCKOUT && p != BT_OFF && running) NimBLEDevice::startAdvertising();
  }
  uint32_t onPassKeyDisplay() override {
    uint32_t code = esp_random() % 1000000;
    xSemaphoreTake(vLock, portMAX_DELAY);
    view.code = code;
    xSemaphoreGive(vLock);
    codeShown = true;
    setPhase(BT_CODE);
    buzz(T_BT_CODE);
    return code;
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    if (info.isEncrypted() && info.isAuthenticated()) {
      wrongCodes = 0;
      setPhase(BT_PAIRED);
      buzz(T_BT_PAIRED);
      return;
    }
    server->disconnect(info.getConnHandle());
    // Only a code that was shown and then typed wrong counts. A phone or laptop that
    // still remembers an old pairing fails here without any code, and that must not
    // use up the 3 tries.
    bool counted = codeShown;
    codeShown = false;
    if (!counted) {
      setPhase(BT_WAIT);
      return;
    }
    if (++wrongCodes >= 3) {
      NimBLEDevice::stopAdvertising();
      setPhase(BT_LOCKOUT);
      buzz(T_BT_STOPPED);
    } else {
      setPhase(BT_WAIT);
      buzz(T_BT_WRONG);
    }
  }
};

class CharCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    std::string v = c->getValue();
    NimBLEUUID u = c->getUUID();
    if (u == NimBLEUUID(BT_SSID_UUID)) newSsid = String(v.c_str());
    else if (u == NimBLEUUID(BT_PASS_UUID)) newPass = String(v.c_str());
    else if (u == NimBLEUUID(BT_CTRL_UUID) && v.size()) {
      uint8_t b = (uint8_t)v[0];
      if (b == 1 || b == '1') joinReq = true;
      else if (b == 2 || b == '2') { stopRemote = true; stopReq = true; }
    }
    xSemaphoreTake(vLock, portMAX_DELAY);
    view.offAtMs = millis() + BT_IDLE_OFF_MS;
    xSemaphoreGive(vLock);
  }
  void onRead(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    if (c != chScan) return;
    BtView v = bleView();
    JsonDocument d;
    JsonArray a = d.to<JsonArray>();
    for (int i = 0; i < v.netCount; i++) {
      JsonObject o = a.add<JsonObject>();
      o["ssid"] = v.nets[i];
      o["rssi"] = v.rssi[i];
    }
    String s;
    serializeJson(d, s);
    c->setValue(s);
  }
};

static ServerCb serverCb;
static CharCb charCb;

static void collectScan() {
  int n = WiFi.scanComplete();
  if (n < 0) return;
  struct Net { String s; int r; };
  Net best[6];
  int k = 0;
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (!s.length()) continue;
    bool dup = false;
    for (int j = 0; j < k; j++) if (best[j].s == s) dup = true;
    if (dup) continue;
    int r = WiFi.RSSI(i);
    if (k < 6) best[k++] = {s, r};
    else {
      int w = 0;
      for (int j = 1; j < 6; j++) if (best[j].r < best[w].r) w = j;
      if (r > best[w].r) best[w] = {s, r};
    }
  }
  for (int i = 0; i < k; i++)
    for (int j = i + 1; j < k; j++)
      if (best[j].r > best[i].r) { Net t = best[i]; best[i] = best[j]; best[j] = t; }
  xSemaphoreTake(vLock, portMAX_DELAY);
  view.netCount = k;
  for (int i = 0; i < k; i++) {
    strlcpy(view.nets[i], best[i].s.c_str(), sizeof(view.nets[i]));
    view.rssi[i] = best[i].r;
  }
  xSemaphoreGive(vLock);
  WiFi.scanDelete();
}

static void doJoin() {
  String ssid = newSsid, pass = newPass;
  xSemaphoreTake(vLock, portMAX_DELAY);
  strlcpy(view.ssid, ssid.c_str(), sizeof(view.ssid));
  xSemaphoreGive(vLock);
  setPhase(BT_CONN);
  notifyStat("{\"state\":\"CONNECTING\"}");
  String oldSsid = net.ssid, oldPass = net.pass;
  WiFi.disconnect(false, false);
  vTaskDelay(pdMS_TO_TICKS(200));
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t t0 = millis();
  wl_status_t s = WL_IDLE_STATUS;
  while (millis() - t0 < 15000) {
    s = WiFi.status();
    if (s == WL_CONNECTED || s == WL_CONNECT_FAILED || s == WL_NO_SSID_AVAIL) break;
    vTaskDelay(pdMS_TO_TICKS(250));
  }
  if (s == WL_CONNECTED) {
    net.ssid = ssid;
    net.pass = pass;
    saveNet();
    String ip = WiFi.localIP().toString();
    xSemaphoreTake(vLock, portMAX_DELAY);
    strlcpy(view.ip, ip.c_str(), sizeof(view.ip));
    view.offAtMs = millis() + BT_IDLE_OFF_MS;
    xSemaphoreGive(vLock);
    setPhase(BT_OK);
    buzz(T_BT_JOINED);
    notifyStat("{\"state\":\"CONNECTED\",\"ip\":\"" + ip + "\",\"ssid\":\"" + ssid + "\"}");
    return;
  }
  const char* why = s == WL_NO_SSID_AVAIL ? "Network not found" : s == WL_CONNECT_FAILED ? "Wrong password" : "Could not join";
  xSemaphoreTake(vLock, portMAX_DELAY);
  strlcpy(view.why, why, sizeof(view.why));
  xSemaphoreGive(vLock);
  setPhase(BT_FAIL);
  buzz(T_BT_JOIN_FAIL);
  notifyStat(String("{\"state\":\"FAILED\",\"why\":\"") + why + "\"}");
  WiFi.disconnect(false, false);
  if (oldSsid.length()) WiFi.begin(oldSsid.c_str(), oldPass.c_str());
}

static void shutdown(bool remote, bool automatic) {
  NimBLEDevice::stopAdvertising();
  NimBLEDevice::deinit(true);
  server = nullptr;
  chStat = chScan = nullptr;
  running = false;
  lockState();
  st.btOn = false;
  unlockState();
  publish();
  if (remote || automatic) {
    setPhase(BT_OFF);
    buzz(automatic ? T_BT_AUTO_OFF : T_BT_OFF);
  } else {
    setPhase(BT_IDLE);
  }
}

static void bleTask(void*) {
  uint32_t lastScan = 0;
  for (;;) {
    vTaskDelay(pdMS_TO_TICKS(200));
    uint32_t now = millis();
    if (stopReq) {
      bool remote = stopRemote;
      stopReq = false;
      stopRemote = false;
      shutdown(remote, false);
      break;
    }
    BtView v = bleView();
    if ((int32_t)(now - v.offAtMs) >= 0 && v.phase != BT_CONN) {
      shutdown(false, true);
      break;
    }
    if (joinReq) {
      joinReq = false;
      doJoin();
    }
    if (retryReq) {
      retryReq = false;
      setPhase(connHandle != 0xFFFF ? BT_PAIRED : BT_WAIT);
    }
    if (v.phase == BT_CODE && now - v.phaseMs >= BT_CODE_MS && connHandle != 0xFFFF) {
      server->disconnect(connHandle);
      setPhase(BT_WAIT);
    }
    if (v.phase == BT_LOCKOUT && now - v.phaseMs >= BT_LOCKOUT_MS) {
      wrongCodes = 0;
      setPhase(BT_WAIT);
      NimBLEDevice::startAdvertising();
      buzz(T_BT_WAIT);
    }
    collectScan();
    if (now - lastScan > 30000 && v.phase != BT_CONN && WiFi.scanComplete() != WIFI_SCAN_RUNNING) {
      lastScan = now;
      WiFi.scanNetworks(true);
    }
  }
  hBle = nullptr;
  vTaskDelete(nullptr);
}

void bleStart() {
  if (!vLock) vLock = xSemaphoreCreateMutex();
  if (running) return;
  running = true;
  wrongCodes = 0;
  stopReq = false;
  stopRemote = false;
  joinReq = false;
  retryReq = false;
  xSemaphoreTake(vLock, portMAX_DELAY);
  memset(&view, 0, sizeof(view));
  view.offAtMs = millis() + BT_IDLE_OFF_MS;
  xSemaphoreGive(vLock);
  setPhase(BT_WAIT);
  lockState();
  st.btOn = true;
  unlockState();
  publish();

  NimBLEDevice::init(BIN_BLE_NAME);
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCb, false);
  NimBLEService* svc = server->createService(BT_SVC_UUID);
  const uint32_t W = NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN;
  const uint32_t R = NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN;
  svc->createCharacteristic(BT_SSID_UUID, W)->setCallbacks(&charCb);
  svc->createCharacteristic(BT_PASS_UUID, W)->setCallbacks(&charCb);
  svc->createCharacteristic(BT_CTRL_UUID, W)->setCallbacks(&charCb);
  chStat = svc->createCharacteristic(BT_STAT_UUID, R | NIMBLE_PROPERTY::NOTIFY);
  chStat->setValue("{\"state\":\"WAITING\"}");
  chScan = svc->createCharacteristic(BT_SCAN_UUID, R);
  chScan->setCallbacks(&charCb);
  server->start();
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->setName(BIN_BLE_NAME);
  adv->addServiceUUID(BT_SVC_UUID);
  adv->enableScanResponse(true);
  adv->start();
  WiFi.scanNetworks(true);
  buzz(T_BT_WAIT);
  xTaskCreatePinnedToCore(bleTask, "ble", 6144, nullptr, 1, &hBle, 0);
}

void bleStop(bool fromRemote) {
  if (!running) return;
  stopRemote = fromRemote;
  stopReq = true;
}

void bleRetry() { retryReq = true; }
