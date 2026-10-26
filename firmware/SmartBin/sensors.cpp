#include "sensors.h"
#include "state.h"
#include "buzzer.h"
#include <Wire.h>
#include <Adafruit_BME680.h>
#include <HX711.h>

static Adafruit_BME680 bme(&Wire);
static uint8_t imuAddr = 0;

static bool imuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(imuAddr);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

static bool imuRead(uint8_t reg, uint8_t* buf, uint8_t len) {
  Wire.beginTransmission(imuAddr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(imuAddr, len) != len) return false;
  for (uint8_t i = 0; i < len; i++) buf[i] = Wire.read();
  return true;
}

static bool imuStart() {
  const uint8_t addrs[2] = {0x68, 0x69};
  uint8_t id = 0;
  for (uint8_t a : addrs) {
    imuAddr = a;
    if (imuRead(0x00, &id, 1) && id == 0xD1) break;
    id = 0;
  }
  if (id != 0xD1) return false;
  imuWrite(0x7E, 0xB6);
  delay(100);
  imuWrite(0x40, 0x28);
  imuWrite(0x41, 0x03);
  for (int i = 0; i < 3; i++) {
    imuWrite(0x7E, 0x11);
    delay(10);
    uint8_t pmu = 0;
    if (imuRead(0x03, &pmu, 1) && ((pmu >> 4) & 3) == 1) return true;
  }
  return false;
}

static bool imuAccel(int16_t a[3]) {
  uint8_t d[6];
  if (!imuRead(0x12, d, 6)) return false;
  a[0] = (int16_t)(d[1] << 8 | d[0]);
  a[1] = (int16_t)(d[3] << 8 | d[2]);
  a[2] = (int16_t)(d[5] << 8 | d[4]);
  return true;
}
static HX711 scale;

static volatile bool reqTare = false, reqCalW = false, reqGas = false, reqImuRef = false;
static volatile float reqGrams = 0;

static int32_t hxOffset = 0;
static float hxScale = 420.0f;
static float imuRef[3] = {0, 0, 1};

static float gasBaseline = 0;
static uint32_t gasStartMs = 0;
static bool baselineFromNvs = false;

void requestTare() { reqTare = true; }
void requestCalWeight(float grams) { reqGrams = grams; reqCalW = true; }
void requestGasReset() { reqGas = true; }
void requestImuReference() { reqImuRef = true; }

int airWarmLeft() {
  uint32_t burn = baselineFromNvs ? 120 : 300;
  uint32_t age = (millis() - gasStartMs) / 1000;
  return age < burn ? (int)(burn - age) : 0;
}

void recomputeView() {
  Reading v = st.raw;
  const Sim& s = sim;
  const Cal& c = cal;
  if (s.mask & 1) {
    v.fill = constrain(s.fill, 0, 100);
    v.distance_cm = c.d_empty - v.fill / 100.0f * (c.d_empty - c.d_full);
  }
  if (s.mask & 2) v.weight_g = s.weight;
  if (s.mask & 4) {
    v.iaq = s.gas;
    v.iaq_acc = 3;
  }
  if (s.mask & 8) {
    v.temp = s.temp;
    v.temp_raw = s.temp - c.temp_off;
    v.humidity = s.humidity;
  }
  if (s.mask & 16) v.tilt = s.tamper ? max(c.tilt_th + 8.0f, 38.0f) : 0.0f;
  if (s.mask & 32) v.motion = s.motion;
  st.v = v;
}

void sensorsInit() {
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(100000);

  pinMode(PIN_TRIG, OUTPUT);
  digitalWrite(PIN_TRIG, LOW);
  pinMode(PIN_ECHO, INPUT);

  bool bmeOk = bme.begin(0x76) || bme.begin(0x77);
  if (bmeOk) {
    bme.setTemperatureOversampling(BME680_OS_8X);
    bme.setHumidityOversampling(BME680_OS_2X);
    bme.setPressureOversampling(BME680_OS_4X);
    bme.setIIRFilterSize(BME680_FILTER_SIZE_3);
    bme.setGasHeater(320, 150);
  }

  bool imuOk = imuStart();
  float x, y, z;
  if (loadImuRef(x, y, z)) {
    imuRef[0] = x; imuRef[1] = y; imuRef[2] = z;
    Serial.printf("[imu] upright reference %.2f %.2f %.2f\n", x, y, z);
  } else {
    reqImuRef = true;
    Serial.println("[imu] no upright reference saved, using the first reading");
  }

  scale.begin(PIN_HX_DT, PIN_HX_SCK);
  loadHx(hxOffset, hxScale);
  if (fabsf(hxScale) < 0.001f) hxScale = 420.0f;

  gasBaseline = loadGasBaseline();
  baselineFromNvs = gasBaseline > 0;
  gasStartMs = millis();

  lockState();
  st.okBME = bmeOk;
  st.okIMU = imuOk;
  unlockState();
}

static float median5(float* a, int n) {
  float b[5];
  for (int i = 0; i < n; i++) b[i] = a[i];
  for (int i = 1; i < n; i++)
    for (int j = i; j > 0 && b[j - 1] > b[j]; j--) { float t = b[j]; b[j] = b[j - 1]; b[j - 1] = t; }
  return b[n / 2];
}

static float iaqFrom(float gasOhm, float hum, uint8_t& acc) {
  uint32_t age = (millis() - gasStartMs) / 1000;
  uint32_t burn = baselineFromNvs ? 120 : 300;
  if (age < burn) {
    if (gasOhm > gasBaseline) gasBaseline = gasOhm;
    acc = 0;
    return NAN;
  }
  if (gasOhm > gasBaseline) gasBaseline = gasOhm;
  else gasBaseline = gasBaseline * 0.99995f + gasOhm * 0.00005f;
  acc = age < burn + 600 ? 1 : age < burn + 1800 ? 2 : 3;
  const float humBase = 40.0f, humWeight = 0.25f;
  float hOff = hum - humBase;
  float humScore = hOff > 0 ? (100.0f - humBase - hOff) / (100.0f - humBase) * humWeight * 100.0f
                            : (humBase + hOff) / humBase * humWeight * 100.0f;
  float gOff = gasBaseline - gasOhm;
  float gasScore = gOff > 0 ? (gasOhm / gasBaseline) * (100.0f - humWeight * 100.0f) : 100.0f - humWeight * 100.0f;
  float quality = constrain(humScore + gasScore, 0.0f, 100.0f);
  return (100.0f - quality) * 5.0f;
}

void sensorTask(void*) {
  float dist[5];
  int distN = 0, distI = 0, echoMiss = 0;
  float hxBuf[5];
  int hxN = 0, hxI = 0;
  int32_t tareAcc = 0;
  int64_t calAcc = 0;
  int tareN = 0, calN = 0;
  uint32_t lastTrig = 0, lastImu = 0, lastHxOk = millis(), lastEnv = 0, envReadyAt = 0, lastRate = 0, lastBaseSave = millis();
  bool envPending = false;
  int bmeFail = 0, imuFail = 0, sameCount = 0;
  int16_t lastA[3] = {0, 0, 0};
  float tiltF = 0;
  float rateBuf[31];
  int rateN = 0;
  uint8_t lastAcc = 0;
  bool firstEnv = false;
  Reading pub;
  bool pubFaults[4] = {false, false, false, false};
  uint32_t bootStart = millis();

  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(50));
    uint32_t now = millis();
    Cal c = calSnapshot();
    bool changed = false;

    if (now - lastTrig >= 200) {
      lastTrig = now;
      digitalWrite(PIN_TRIG, HIGH);
      delayMicroseconds(10);
      digitalWrite(PIN_TRIG, LOW);
      unsigned long us = pulseIn(PIN_ECHO, HIGH, 25000);
      if (us > 0) {
        float cm = us / 58.0f;
        if (cm >= 2 && cm <= 400) {
          dist[distI] = cm;
          distI = (distI + 1) % 5;
          if (distN < 5) distN++;
          echoMiss = 0;
        }
      } else if (++echoMiss > 10) {
        distN = 0;
      }
      bool ok = distN >= 3;
      float d = ok ? median5(dist, distN) : 0;
      float fill = ok ? constrain((c.d_empty - d) / (c.d_empty - c.d_full) * 100.0f, 0.0f, 100.0f) : 0;
      lockState();
      if (st.okHC != ok) { st.okHC = ok; changed = true; }
      st.raw.distance_cm = d;
      st.raw.fill = fill;
      unlockState();
    }

    if ((ev & EV_IMU) || now - lastImu >= 200) {
      lastImu = now;
      int16_t a[3] = {0, 0, 0};
      bool ok = imuAccel(a) && (a[0] || a[1] || a[2]);
      if (ok && a[0] == lastA[0] && a[1] == lastA[1] && a[2] == lastA[2]) {
        if (++sameCount >= 25) {
          sameCount = 0;
          ok = false;
          imuFail = 10;
          imuStart();
        }
      } else {
        sameCount = 0;
      }
      if (ok) {
        memcpy(lastA, a, sizeof(lastA));
        imuFail = 0;
        float x = a[0], y = a[1], z = a[2];
        float m = sqrtf(x * x + y * y + z * z);
        if (m > 1) {
          x /= m; y /= m; z /= m;
          if (reqImuRef) {
            reqImuRef = false;
            imuRef[0] = x; imuRef[1] = y; imuRef[2] = z;
            saveImuRef(x, y, z);
          }
          float dot = constrain(x * imuRef[0] + y * imuRef[1] + z * imuRef[2], -1.0f, 1.0f);
          float deg = acosf(dot) * 57.29578f;
          tiltF = tiltF * 0.6f + deg * 0.4f;
        }
      } else if (++imuFail > 10) {
        tiltF = 0;
      }
      lockState();
      bool okNow = imuFail <= 10;
      if (st.okIMU != okNow) { st.okIMU = okNow; changed = true; }
      st.raw.tilt = tiltF;
      unlockState();
    }

    if (scale.is_ready()) {
      long raw = scale.read();
      lastHxOk = now;
      if (reqTare) {
        tareAcc += raw;
        if (++tareN >= 10) {
          hxOffset = tareAcc / tareN;
          tareAcc = 0; tareN = 0; reqTare = false; hxN = 0;
          saveHx(hxOffset, hxScale);
          buzz(T_CAL_STEP);
        }
      } else if (reqCalW) {
        calAcc += raw;
        if (++calN >= 10) {
          float avg = (float)calAcc / calN;
          if (reqGrams > 0 && fabsf(avg - hxOffset) > 10) hxScale = (avg - hxOffset) / reqGrams;
          calAcc = 0; calN = 0; reqCalW = false; hxN = 0;
          saveHx(hxOffset, hxScale);
          buzz(T_CAL_STEP);
        }
      }
      hxBuf[hxI] = (raw - hxOffset) / hxScale;
      hxI = (hxI + 1) % 5;
      if (hxN < 5) hxN++;
      float sum = 0;
      for (int i = 0; i < hxN; i++) sum += hxBuf[i];
      float g = sum / hxN;
      if (fabsf(g) < 5) g = 0;
      lockState();
      if (!st.okHX) { st.okHX = true; changed = true; }
      st.raw.weight_g = g;
      unlockState();
    } else if (now - lastHxOk > 1500) {
      lockState();
      if (st.okHX) { st.okHX = false; changed = true; }
      unlockState();
    }

    if (reqGas) {
      reqGas = false;
      gasBaseline = 0;
      baselineFromNvs = false;
      gasStartMs = now;
      saveGasBaseline(0);
    }

    if (!envPending && now - lastEnv >= 3000) {
      lastEnv = now;
      envReadyAt = bme.beginReading();
      envPending = envReadyAt != 0;
      if (!envPending && ++bmeFail >= 3) {
        lockState();
        if (st.okBME) { st.okBME = false; changed = true; }
        unlockState();
        if (bme.begin(0x76) || bme.begin(0x77)) bme.setGasHeater(320, 150);
      }
    }
    if (envPending && now >= envReadyAt) {
      envPending = false;
      if (bme.endReading()) {
        bmeFail = 0;
        firstEnv = true;
        uint8_t acc = 0;
        float hum = constrain(bme.humidity + c.hum_off, 0.0f, 100.0f);
        float iaq = bme.gas_resistance > 0 ? iaqFrom(bme.gas_resistance, hum, acc) : NAN;
        lockState();
        if (!st.okBME) { st.okBME = true; changed = true; }
        st.raw.temp_raw = bme.temperature;
        st.raw.temp = bme.temperature + c.temp_off;
        st.raw.humidity = hum;
        st.raw.pressure = bme.pressure / 100.0f + c.press_off;
        st.raw.gas_k = bme.gas_resistance / 1000.0f;
        if (!isnan(iaq)) st.raw.iaq = constrain(iaq, 0.0f, 500.0f);
        st.raw.iaq_acc = acc;
        unlockState();
        if (acc >= 1 && lastAcc == 0) {
          buzz(T_AIR_READY);
          char meta[72];
          snprintf(meta, sizeof(meta), "%s air quality readings have started", BIN_LABEL);
          raiseAlert("sensor", "success", "Air sensor ready", meta);
        }
        lastAcc = acc;
        if (acc >= 3 && now - lastBaseSave > 3600000UL) {
          lastBaseSave = now;
          saveGasBaseline(gasBaseline);
        }
      } else if (++bmeFail >= 3) {
        lockState();
        if (st.okBME) { st.okBME = false; changed = true; }
        unlockState();
      }
    }

    if (now - lastRate >= 60000 || lastRate == 0) {
      lastRate = now;
      lockState();
      float f = st.v.fill;
      bool ok = st.okHC || (sim.mask & 1);
      unlockState();
      if (ok) {
        if (rateN && f < rateBuf[rateN - 1] - 5) rateN = 0;
        if (rateN == 31) { memmove(rateBuf, rateBuf + 1, 30 * sizeof(float)); rateN = 30; }
        rateBuf[rateN++] = f;
        float rate = rateN >= 5 ? (rateBuf[rateN - 1] - rateBuf[0]) / ((rateN - 1) / 60.0f) : 0;
        lockState();
        st.fillRatePerHour = rate > 0.05f ? rate : 0;
        unlockState();
      }
    }

    lockState();
    if (st.booting && (firstEnv || !st.okBME) && now - bootStart > 3500) {
      st.booting = false;
      changed = true;
    }
    recomputeView();
    const Reading& v = st.v;
    bool faults[4] = {st.okHC, st.okHX, st.okBME, st.okIMU};
    if (fabsf(v.fill - pub.fill) >= 1 || fabsf(v.weight_g - pub.weight_g) >= 20 || fabsf(v.temp - pub.temp) >= 0.2f ||
        fabsf(v.humidity - pub.humidity) >= 1 || fabsf(v.iaq - pub.iaq) >= 5 || fabsf(v.tilt - pub.tilt) >= 1 ||
        fabsf(v.pressure - pub.pressure) >= 0.5f || v.iaq_acc != pub.iaq_acc || memcmp(faults, pubFaults, sizeof(faults)))
      changed = true;
    if (changed) {
      pub = v;
      memcpy(pubFaults, faults, sizeof(faults));
    }
    unlockState();
    if (changed) {
      publish();
      if (hControl) xTaskNotify(hControl, EV_STATE, eSetBits);
    }
  }
}
