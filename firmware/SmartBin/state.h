#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include "config.h"

enum BinStatus : uint8_t { ST_OK = 0, ST_LOCKED, ST_TAMPER, ST_COLLECTING, ST_CALIBRATING };
extern const char* const STATUS_NAMES[5];

struct Cal {
  float d_empty = 30, d_full = 5, fill_th = 80, temp_th = 40, hum_th = 85, gas_th = 200, tilt_th = 30, weight_cap = 4500;
  uint8_t auto_lock = 0;
  int16_t servo_closed = 0, servo_open = 90;
  uint16_t collect_pin = 0;
  uint16_t pir_hold_s = 5, buzz_s = 2;
  float temp_off = -2, hum_off = 0, press_off = 0;
  uint8_t buzz_on = 1, buzz_vol = 2, buzz_ui = 1, buzz_lid = 0;
  uint16_t buzz_repeat_s = 30;
  uint8_t quiet_on = 0;
  uint16_t quiet_from = 1320, quiet_to = 360;
};

struct Sim {
  uint8_t mask = 0;
  float fill = 60, weight = 2000, gas = 120, temp = 28, humidity = 60;
  bool tamper = false, motion = false, lid = false;
};

struct Reading {
  float fill = 0, distance_cm = 0, weight_g = 0, temp = 0, temp_raw = 0, humidity = 0, pressure = 0, iaq = 0, tilt = 0;
  uint8_t iaq_acc = 0;
  bool motion = false;
};

struct LastCmd {
  char cmd[20] = "";
  uint64_t ts = 0;
  bool ok = false;
};

struct BinState {
  Reading raw;            // what the sensors read
  Reading v;              // what everyone sees (raw with simulation applied)
  bool lidOpen = false;
  BinStatus status = ST_OK;
  bool locked = false, collecting = false, calibrating = false, autoLocked = false;
  uint8_t tamperPhase = 0;            // 0 none, 1 countdown, 2 latched
  uint32_t tamperStartMs = 0;
  bool okHC = false, okHX = false, okBME = false, okIMU = false;
  bool booting = true;
  bool wifi = false, cloudOk = false, tsOk = false, btOn = false;
  int rssi = 0;
  uint32_t lidCloseAtMs = 0;          // 0 = no countdown; lid holds while motion is high
  bool alarmFull = false, alarmEnv = false, buzzerOn = false;
  uint32_t buzzerUntilMs = 0;
  float fillRatePerHour = 0;
  uint8_t calSteps = 0;               // bit 0 tare, bit 1 empty distance, bit 2 full distance
  uint32_t doneAtMs = 0;              // when COLLECT_DONE ran, for the A5 screen
  LastCmd last;
  uint32_t version = 0;
  uint32_t calVersion = 0;
};

enum CmdSource : uint8_t { SRC_LOCAL = 0, SRC_SCREEN, SRC_FIREBASE, SRC_TALKBACK };

struct Command {
  char cmd[20];
  float grams;
  char sound[16];
  uint8_t src;
  char ref[28];
};

struct CmdResult {
  char cmd[20];
  bool ok;
  char why[40];
  uint8_t src;
  char ref[28];
  uint64_t ts;
};

struct AlertEv {
  char kind[10];
  char sev[10];
  char title[40];
  char meta[72];
};

enum : uint32_t {
  EV_PIR = 1 << 0, EV_TOUCH = 1 << 1, EV_IMU = 1 << 2, EV_STATE = 1 << 3, EV_CMD = 1 << 4,
  EV_CAL = 1 << 5, EV_WIFI = 1 << 6, EV_ALERT = 1 << 7, EV_RESULT = 1 << 8, EV_BT = 1 << 9,
};

extern BinState st;
extern Cal cal;
extern Sim sim;
extern SemaphoreHandle_t stLock;
extern QueueHandle_t cmdQueue, resultQueue, alertQueue;
extern TaskHandle_t hControl, hSensor, hUi, hCloud, hPush;

struct NetCfg {
  String ssid, pass;
  String fbUrl, fbKey, fbEmail, fbPass;
  String tsServer, tsWriteKey, tbId, tbKey;
};
extern NetCfg net;

void stateInit();
void lockState();
void unlockState();
BinState snapshot();
Cal calSnapshot();
Sim simSnapshot();
void publish(uint32_t extraBits = 0);
void notifyAll(uint32_t bits);

void saveCal();
void loadCal();
void saveNet();
void loadNet();
uint8_t loadTheme();
void saveTheme(uint8_t dark);
void saveHx(int32_t offset, float scale);
bool loadHx(int32_t& offset, float& scale);
void saveGasBaseline(float g);
float loadGasBaseline();
void saveImuRef(float x, float y, float z);
bool loadImuRef(float& x, float& y, float& z);

void calToJson(const Cal& c, JsonObject o);
bool calFromJson(JsonObjectConst o, Cal& c);
void statusToJson(const BinState& s, const Cal& c, const Sim& sm, JsonObject o, bool withCal, bool forCloud);
String statusJson(bool withCal = true, bool forCloud = false);
String localIp();

bool sendCommand(const char* cmd, uint8_t src, float grams = 0, const char* sound = nullptr, const char* ref = nullptr);
bool validateCommand(const char* cmd, const BinState& s, String& why);
void raiseAlert(const char* kind, const char* sev, const char* title, const char* meta);

uint64_t nowMs();
bool clockSet();
int localMinuteOfDay();
void monthKeyUtc(char* out, size_t n);
