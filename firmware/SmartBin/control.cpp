#include "control.h"
#include "state.h"
#include "sensors.h"
#include "buzzer.h"
#include "ui.h"
#include <ESP32Servo.h>

static Servo lid;
static int lidAngle = -1;

void IRAM_ATTR isrPir() {
  BaseType_t woke = pdFALSE;
  if (hControl) xTaskNotifyFromISR(hControl, EV_PIR, eSetBits, &woke);
  portYIELD_FROM_ISR(woke);
}

static void led(bool r, bool g, bool b) {
  digitalWrite(PIN_LED_R, r ^ LED_ACTIVE_LOW);
  digitalWrite(PIN_LED_G, g ^ LED_ACTIVE_LOW);
  digitalWrite(PIN_LED_B, b ^ LED_ACTIVE_LOW);
}

void controlInit() {
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_LED_B, OUTPUT);
  led(false, false, true);
  pinMode(PIN_PIR, INPUT);
  ESP32PWM::allocateTimer(0);
  lid.setPeriodHertz(50);
  lid.attach(PIN_SERVO, 500, 2400);
  Cal c = calSnapshot();
  lid.write(c.servo_closed);
  lidAngle = c.servo_closed;
}

static uint32_t lidMovedMs = 0;

static void setLid(int angle) {
  if (angle == lidAngle) return;
  if (!lid.attached()) lid.attach(PIN_SERVO, 500, 2400);
  lid.write(angle);
  lidAngle = angle;
  lidMovedMs = millis();
}

static void releaseServo(int closedAngle, uint32_t now) {
#if SERVO_RELEASE_WHEN_CLOSED
  if (lid.attached() && lidAngle == closedAngle && now - lidMovedMs > 800) lid.detach();
#endif
}

static void finish(const Command& c, bool ok, const char* why) {
  lockState();
  strlcpy(st.last.cmd, c.cmd, sizeof(st.last.cmd));
  st.last.ts = nowMs();
  st.last.ok = ok;
  unlockState();
  if (c.src == SRC_FIREBASE && c.ref[0]) {
    CmdResult r{};
    strlcpy(r.cmd, c.cmd, sizeof(r.cmd));
    r.ok = ok;
    strlcpy(r.why, why ? why : "", sizeof(r.why));
    r.src = c.src;
    strlcpy(r.ref, c.ref, sizeof(r.ref));
    r.ts = nowMs();
    xQueueSend(resultQueue, &r, 0);
  }
  if (!ok && c.src != SRC_SCREEN) buzz(T_CMD_FAIL);
  publish(EV_RESULT);
}

static void runCommand(const Command& c) {
  BinState s = snapshot();
  String why;
  if (!validateCommand(c.cmd, s, why)) {
    finish(c, false, why.c_str());
    return;
  }
  const char* k = c.cmd;
  char meta[72];
  lockState();
  if (!strcmp(k, "LOCK")) {
    st.locked = true;
    st.autoLocked = false;
  } else if (!strcmp(k, "UNLOCK")) {
    st.locked = false;
    st.autoLocked = false;
  } else if (!strcmp(k, "CLEAR_TAMPER")) {
    st.tamperPhase = 0;
  } else if (!strcmp(k, "COLLECT")) {
    st.collecting = true;
  } else if (!strcmp(k, "COLLECT_DONE")) {
    if (st.collecting) st.doneAtMs = millis();
    st.collecting = false;
    if (st.autoLocked) { st.locked = false; st.autoLocked = false; }
  } else if (!strcmp(k, "CALIBRATE_START")) {
    st.calibrating = true;
    st.calSteps = 0;
  } else if (!strcmp(k, "CALIBRATE_END")) {
    st.calibrating = false;
  } else if (!strcmp(k, "TARE")) {
    st.calSteps |= 1;
  }
  unlockState();

  if (!strcmp(k, "LOCK")) {
    buzz(T_LOCK);
    snprintf(meta, sizeof(meta), "%s locked", BIN_LABEL);
    raiseAlert("lock", "info", "Bin locked", meta);
  } else if (!strcmp(k, "UNLOCK")) {
    buzz(T_UNLOCK);
    snprintf(meta, sizeof(meta), "%s is open for use again", BIN_LABEL);
    raiseAlert("lock", "success", "Bin unlocked", meta);
  } else if (!strcmp(k, "CLEAR_TAMPER")) {
    buzz(T_TAMPER_SETTLED);
  } else if (!strcmp(k, "COLLECT")) {
    if (c.src != SRC_SCREEN) buzz(T_PIN_OK);
    snprintf(meta, sizeof(meta), "%s pickup started", BIN_LABEL);
    raiseAlert("pickup", "info", "Collection started", meta);
  } else if (!strcmp(k, "COLLECT_DONE")) {
    buzz(T_DONE);
    snprintf(meta, sizeof(meta), "%s is back to normal", BIN_LABEL);
    raiseAlert("pickup", "success", "Collection complete", meta);
  } else if (!strcmp(k, "CALIBRATE_START")) {
    requestImuReference();
    buzz(T_CAL_START);
    snprintf(meta, sizeof(meta), "%s is being calibrated", BIN_LABEL);
    raiseAlert("setup", "info", "Calibration started", meta);
  } else if (!strcmp(k, "CALIBRATE_END")) {
    buzz(c.src == SRC_SCREEN ? T_CAL_CANCEL : T_SAVED);
  } else if (!strcmp(k, "TARE")) {
    requestTare();
  } else if (!strcmp(k, "CAL_WEIGHT")) {
    requestCalWeight(c.grams > 0 ? c.grams : 500);
  } else if (!strcmp(k, "RESET_GAS")) {
    requestGasReset();
    buzz(T_CAL_STEP);
  } else if (!strcmp(k, "TOUCH_CAL")) {
    uiStartTouchCal();
  } else if (!strcmp(k, "TOUCH_CAL_RESET")) {
    uiResetTouchCal();
  } else if (!strcmp(k, "BUZZ_TEST")) {
    if (!buzzByName(c.sound[0] ? c.sound : "full")) {
      finish(c, false, "Unknown sound");
      return;
    }
  }
  finish(c, true, nullptr);
}

static void updateLed(const BinState& s, const Cal& c, uint32_t now) {
  bool blink = (now / 500) % 2;
  if (s.btOn) led(false, false, true);
  else if (s.tamperPhase) led(blink, false, false);
  else if (s.calibrating) led(false, true, true);
  else if (s.collecting) led(false, true, false);
  else if (s.status == ST_LOCKED) led(true, false, true);
  else if (s.alarmFull) led(blink, false, false);
  else if (s.v.fill >= 50) led(true, true, false);
  else led(false, true, false);
}

void controlTask(void*) {
  uint32_t lastTick = 0, lastWave = 0, lastFullBuzz = 0, lastEnvBuzz = 0, lastTamperTick = 0;
  bool prevFull = false, prevEnv = false, prevHC = true, prevHX = true, prevBME = true;
  bool prevMotion = false, firstPass = true, ignoredNoted = false;
  BinStatus prevStatus = ST_OK;
  uint8_t prevMask = 0;

  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(100));

    Command c;
    while (xQueueReceive(cmdQueue, &c, 0) == pdTRUE) runCommand(c);

    uint32_t now = millis();
    Cal cl = calSnapshot();
    bool pir = digitalRead(PIN_PIR);
    bool changed = false;
    char meta[72];

    lockState();
    st.raw.motion = pir;
    recomputeView();
    bool motion = st.v.motion;
    bool hcOk = st.okHC || (sim.mask & 1);
    bool fullNow = hcOk && st.v.fill >= cl.fill_th && !st.collecting && !st.calibrating;
    bool tiltBad = (st.okIMU || (sim.mask & 16)) && st.v.tilt > cl.tilt_th && !st.collecting && !st.calibrating;

    if (tiltBad && st.tamperPhase == 0) {
      st.tamperPhase = 1;
      st.tamperStartMs = now;
      changed = true;
    } else if (!tiltBad && st.tamperPhase == 1) {
      st.tamperPhase = 0;
      changed = true;
      unlockState();
      buzz(T_TAMPER_SETTLED);
      lockState();
    } else if (st.tamperPhase == 1 && now - st.tamperStartMs >= TAMPER_COUNTDOWN_S * 1000) {
      st.tamperPhase = 2;
      changed = true;
      snprintf(meta, sizeof(meta), "%s was moved or tilted (%.0f deg)", BIN_LABEL, st.v.tilt);
      unlockState();
      raiseAlert("tamper", "critical", "Tamper detected", meta);
      lockState();
    }

    if (cl.auto_lock && fullNow && !st.locked && !st.collecting && !st.tamperPhase) {
      st.locked = true;
      st.autoLocked = true;
      changed = true;
      snprintf(meta, sizeof(meta), "%s locked itself at %.0f%%", BIN_LABEL, st.v.fill);
      unlockState();
      buzz(T_LOCK);
      raiseAlert("lock", "info", "Bin locked", meta);
      lockState();
    }

    BinStatus status = st.calibrating ? ST_CALIBRATING : st.collecting ? ST_COLLECTING
                     : st.tamperPhase ? ST_TAMPER : st.locked ? ST_LOCKED : ST_OK;
    if (status != st.status) { st.status = status; changed = true; }

    bool wantOpen;
    if (status == ST_COLLECTING) {
      wantOpen = true;
      st.lidCloseAtMs = 0;
    } else if (status != ST_OK) {
      wantOpen = false;
      st.lidCloseAtMs = 0;
    } else if (sim.mask & 64) {
      wantOpen = sim.lid;
      st.lidCloseAtMs = 0;
    } else if (motion && (st.lidOpen || st.lidArmed)) {
      // After the lid shuts, the PIR has to go quiet once (after lid_close_s) before it can
      // open the lid again. The closing servo often retriggers the PIR, which then stays high
      // for its own hold time; without this the lid opened again with nobody there.
      wantOpen = true;
      st.lidCloseAtMs = 0;
    } else if (st.lidOpen && st.lidCloseAtMs == 0) {
      st.lidCloseAtMs = now + cl.pir_hold_s * 1000UL;
      wantOpen = true;
    } else if (st.lidOpen && (int32_t)(now - st.lidCloseAtMs) < 0) {
      wantOpen = true;
    } else {
      wantOpen = false;
      st.lidCloseAtMs = 0;
    }
    bool lidChanged = wantOpen != st.lidOpen;
    if (lidChanged) {
      st.lidOpen = wantOpen;
      if (wantOpen) {
        Serial.printf("[lid] open: %s\n", status == ST_COLLECTING ? "pickup" : (sim.mask & 64) ? "simulation" : "motion");
      } else {
        st.lidClosedAtMs = now;
        st.lidArmed = false;
        ignoredNoted = false;
        if (status != ST_OK) Serial.printf("[lid] closed: %s\n", STATUS_NAMES[status]);
        else Serial.printf("[lid] closed: no motion for %u s; stays shut at least %u s\n", cl.pir_hold_s, cl.lid_close_s);
      }
      changed = true;
    }
    if (!st.lidOpen && !st.lidArmed) {
      bool settled = now - st.lidClosedAtMs >= cl.lid_close_s * 1000UL;
      if (settled && !motion) {
        st.lidArmed = true;
        changed = true;
        Serial.println("[lid] ready: the PIR is low, the next motion opens the lid");
      } else if (settled && motion && !ignoredNoted) {
        ignoredNoted = true;
        changed = true;
        Serial.println("[lid] waiting: the PIR is still high since the lid closed; it must go low first");
      }
    }
    if (motion != prevMotion) changed = true;

    st.alarmFull = fullNow && status == ST_OK;
    bool gasOk = st.okBME || (sim.mask & 4);
    bool envBad = !st.collecting && !st.calibrating &&
                  (((st.okBME || (sim.mask & 8)) && (st.v.temp >= cl.temp_th || st.v.humidity >= cl.hum_th)) ||
                   (gasOk && st.v.iaq_acc >= 1 && st.v.iaq >= cl.gas_th));
    st.alarmEnv = envBad;
    st.buzzerOn = buzzerBusy() && (st.alarmFull || st.tamperPhase || envBad);
    BinState s = st;
    unlockState();

    setLid(s.lidOpen ? cl.servo_open : cl.servo_closed);
    releaseServo(cl.servo_closed, now);
    if (lidChanged) buzz(s.lidOpen ? T_LID_OPEN : T_LID_CLOSE);
    if (motion && !prevMotion && (s.status == ST_LOCKED || s.status == ST_TAMPER) && now - lastWave > 5000) {
      lastWave = now;
      buzz(T_LOCKED_WAVE);
    }
    prevMotion = motion;

    if (s.tamperPhase == 1 && now - lastTamperTick >= 1000) {
      lastTamperTick = now;
      uint32_t left = TAMPER_COUNTDOWN_S - min<uint32_t>(TAMPER_COUNTDOWN_S, (now - s.tamperStartMs) / 1000);
      buzz(left <= 3 ? T_TAMPER_TICK_END : T_TAMPER_TICK);
      changed = true;
    }
    if (s.tamperPhase == 2 && now - lastTamperTick >= TAMPER_ALARM_EVERY_MS) {
      lastTamperTick = now;
      buzz(T_TAMPER_LATCHED);
    }

    if (s.alarmFull && (!prevFull || now - lastFullBuzz >= cl.buzz_repeat_s * 1000UL)) {
      if (!prevFull) {
        snprintf(meta, sizeof(meta), "%s is at %.0f%%", BIN_LABEL, s.v.fill);
        raiseAlert("full", "critical", "Fill threshold passed", meta);
      }
      lastFullBuzz = now;
      buzz(T_FULL);
      lockState();
      st.buzzerUntilMs = now + max<uint32_t>(cl.buzz_s, 1) * 1000UL;
      unlockState();
    }
    prevFull = s.alarmFull;

    if (envBad && (!prevEnv || now - lastEnvBuzz >= 300000)) {
      if (!prevEnv) {
        if (s.v.temp >= cl.temp_th) snprintf(meta, sizeof(meta), "%s at %.1f C, limit %.0f", BIN_LABEL, s.v.temp, cl.temp_th);
        else if (s.v.humidity >= cl.hum_th) snprintf(meta, sizeof(meta), "%s at %.0f%%, limit %.0f%%", BIN_LABEL, s.v.humidity, cl.hum_th);
        else snprintf(meta, sizeof(meta), "%s index %.0f, limit %.0f", BIN_LABEL, s.v.iaq, cl.gas_th);
        raiseAlert("sensor", "warning", "Reading over its limit", meta);
      }
      lastEnvBuzz = now;
      buzz(T_ENV_OVER);
    } else if (!envBad && prevEnv) {
      buzz(T_ENV_OK);
    }
    prevEnv = envBad;

    if (!firstPass && !s.booting) {
      const bool oks[3] = {s.okHC, s.okHX, s.okBME};
      bool* prevs[3] = {&prevHC, &prevHX, &prevBME};
      const char* names[3] = {"HC-SR04", "HX711", "BME680"};
      for (int i = 0; i < 3; i++) {
        if (oks[i] == *prevs[i]) continue;
        *prevs[i] = oks[i];
        buzz(oks[i] ? T_SENSOR_BACK : T_SENSOR_LOST);
        snprintf(meta, sizeof(meta), "%s: %s %s", BIN_LABEL, names[i], oks[i] ? "answers again" : "stopped answering");
        raiseAlert("sensor", oks[i] ? "success" : "warning", oks[i] ? "Sensor back" : "Sensor not answering", meta);
      }
    } else if (!s.booting) {
      prevHC = s.okHC; prevHX = s.okHX; prevBME = s.okBME;
      firstPass = false;
      buzz(s.okHC && s.okHX && s.okBME && s.okIMU ? T_BOOT_OK : T_BOOT_FAULT);
    }

    Sim sm = simSnapshot();
    if ((sm.mask != 0) != (prevMask != 0)) buzz(sm.mask ? T_SIM_ON : T_SIM_OFF);
    prevMask = sm.mask;

    if (s.status != prevStatus) {
      prevStatus = s.status;
      changed = true;
    }
    if (now - lastTick >= 100) {
      lastTick = now;
      updateLed(s, cl, now);
    }
    if (changed) publish();
  }
}
