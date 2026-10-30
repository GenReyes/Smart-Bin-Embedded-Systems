# Smart-Bin: an interrupt-driven ESP32 and an event-driven dashboard

**For:** Genesis (firmware and web dashboard)
**From:** Brian
**Status:** recommendations. The API, Firebase and ThingSpeak names stay exactly as in `Web Dashboard/ESP32-Integration-Guide.md`, so the Android app keeps working whatever you pick here. This guide only changes *how* the firmware and dashboard wait for things.

The idea in one line: **nothing spins in `loop()` asking "did anything happen yet?"**. Hardware interrupts and FreeRTOS tasks wake the code only when something happens, and the dashboard gets pushed a new status instead of asking the bin every second.

> **Update (Oct 2026):** the PIR was replaced by an IR obstacle sensor module on the same pin, GPIO 27, with the same interrupt. Read "PIR" below as "IR sensor". See `firmware/README.md` section 5b.

---

## 1. Is it hard?

Not very, if you build it in the order of section 8. The pin plan (`Documents/Plans/SmartBin_Implementation_Plan_v2.pdf`, pin table) already makes the PIR on GPIO 27 interrupt-based and gives the touch IRQ its own pin, GPIO 36. What takes care is the plumbing around the interrupts: who owns the I2C bus, who owns the SPI bus, and how data moves between tasks safely. Sections 3 and 4 spell that out.

| Piece | Difficulty | Notes |
|---|---|---|
| PIR interrupt | Easy | One ISR that sets a flag |
| Touch IRQ | Easy | The XPT2046 library already supports an IRQ pin |
| Lid timer, Wi-Fi events | Easy | Timeouts and `WiFi.onEvent()` instead of checking every loop |
| Splitting work into FreeRTOS tasks | Medium | The main new idea; sections 3 and 4 |
| HC-SR04 echo by interrupt | Medium | Optional; `pulseIn()` with a timeout is the simple version |
| BMI160 motion interrupt | Medium | Needs one extra wire and a few register writes |
| Dashboard push (SSE) | Easy to medium | One new endpoint on the ESP32, about 40 lines of JS |

**About the battery.** With Wi-Fi on, the radio draws most of the current, so interrupts alone won't make the 18650 last much longer. What you do gain is a bin that reacts at once (lid, alarms, screen) and never freezes while a cloud upload is slow. For the paper, say "responsive and non-blocking", not "low power", unless you measure it.

---

## 2. Which parts use interrupts

Pins are from the plan's pin table.

| Part | Pin(s) | Method | Why |
|---|---|---|---|
| HC-SR501 PIR | 27 | **Interrupt**, `CHANGE` | Motion can come at any time and opens the lid. The module has its own hold time, so its output doesn't bounce. |
| XPT2046 touch IRQ | 36 | **Interrupt**, `FALLING`, then confirm | Goes low on a press. See the GPIO 36 warning in section 6. |
| BMI160 tamper | INT1 to **GPIO 34** (new wire) | **Interrupt** (any-motion) plus a slow check | The chip wakes the code when the bin is moved, then the task measures the tilt. INT1 isn't wired in the current plan; GPIO 34 is free. Without the wire, poll every 100 ms. |
| HC-SR04 echo | 18 (trig on 5) | **Interrupt** on `CHANGE`, or `pulseIn()` with a 25 ms timeout | The ISR timestamps the rising and falling edge, so nothing waits for the echo. |
| HX711 load cell | 32 DT, 14 SCK | **Polled** every 100 ms with `is_ready()` | DT goes low when data is ready, but it also toggles while the bits are clocked out, so an interrupt on it fires during its own read. Polling at the chip's own 10 Hz is simpler and costs nothing. |
| BME680 | I2C 21/22 | **Timed**, every 3 s | It has no interrupt pin. Use `beginReading()` and collect with `endReading()` later so the ~150 ms gas heater wait doesn't block. |
| SG90 servo | 13 | **Timeout** | Hardware PWM (LEDC) holds the position. Only the "close the lid after `pir_hold_s`" needs a timer. |
| Buzzer | 12 | **Queue** | The buzzer task in section 9.2 of the integration guide already works this way. |
| RGB LED | 25, 26, 33 | Set on state change | No timing needed unless it blinks. |
| Wi-Fi | | **Events** (`WiFi.onEvent`) | React to "got IP" and "disconnected" instead of checking `WiFi.status()`. |
| Local API | | **Already event-driven** | ESPAsyncWebServer answers in its own task. |
| Firebase commands | | Polled (2 s / 10 s, as in 6A) | A streaming connection is possible but holds about 40 KB of RAM for TLS. Keep polling unless the heap has room. |
| ThingSpeak TalkBack | | Polled (5 s) | TalkBack has no push. |

---

## 3. The shape of the firmware

`loop()` does nothing. Each job is a FreeRTOS task that sleeps until an interrupt, a queue item, or its own timeout wakes it. A sleeping task uses no CPU.

```
 ISR PIR ───────┐
 ISR echo ──────┤──▶ sensorTask (core 1) ─┐
 ISR BMI160 ────┘    owns I2C (Wire)      │  publish()
                                          ▼
 /api/command ──▶ cmdQueue ──▶ controlTask (core 1) ──▶ BinState (mutex)
 Firebase/TalkBack ──┘        lid, lock, status           │
                                                          │ notify on change
 ISR touch ─────────▶ uiTask (core 1) ◀───────────────────┤
                      owns SPI (TFT + touch)              │
                                                          ├──▶ cloudTask (core 0): Firebase, ThingSpeak
                                                          └──▶ SSE push to dashboards (/api/events)
 buzz() from anywhere ──▶ buzzerTask (integration guide 9.2)
```

| Task | Core | Priority | Wakes on | Stack |
|---|---|---|---|---|
| `controlTask` | 1 | 4 | PIR, commands, lid timeout | 4 KB |
| `sensorTask` | 1 | 3 | echo done, BMI160 INT, 100 ms tick | 4 KB |
| `uiTask` | 1 | 2 | touch, state changed, 1 s clock | 8 KB |
| `buzzerTask` | 1 | 2 | buzzer queue | 2 KB |
| `cloudTask` | 0 | 1 | state changed, its own timers | 8 to 10 KB (TLS) |

Core 0 also runs the Wi-Fi stack, which is why the slow cloud work goes there at low priority.

**Ownership rules** (these prevent most crashes):

- Only `sensorTask` touches I2C (BMI160 and BME680 share 21/22).
- Only `uiTask` touches SPI (the ILI9341 and the XPT2046 share 19/23/35).
- Only `controlTask` moves the servo and changes the lock or status.
- Everything else asks: the web handlers and the cloud task send a command to `cmdQueue`; anyone can call `buzz()`.
- Readings live in one `BinState` struct behind a mutex. Copy it out, unlock, then use the copy.

---

## 4. Code skeleton

Arduino core 3.x, with the maintained `ESP32Async/ESPAsyncWebServer` and `ESP32Async/AsyncTCP`. Names are suggestions.

### 4.1 Shared state and the publish step

```cpp
#include <Arduino.h>
#include "soc/gpio_reg.h"
#include "esp_timer.h"

struct BinState {
  float fill, distance_cm, weight_g, temp, humidity, pressure, iaq, tilt;
  bool motion, lidOpen;
  uint8_t status;          // 0 OK, 1 LOCKED, 2 TAMPER, 3 COLLECTING, 4 CALIBRATING
  uint32_t version;        // goes up on every change
};

BinState st;
SemaphoreHandle_t stLock;  // xSemaphoreCreateMutex() in setup()
TaskHandle_t hControl, hSensor, hUi, hCloud;
QueueHandle_t cmdQueue;    // xQueueCreate(8, sizeof(Command))

enum : uint32_t {          // notification bits
  EV_PIR = 1 << 0, EV_TOUCH = 1 << 1, EV_ECHO = 1 << 2, EV_IMU = 1 << 3,
  EV_STATE = 1 << 4,
};

// Call after changing st. Wakes everyone who shows or sends the state.
void publish() {
  xTaskNotify(hUi, EV_STATE, eSetBits);
  xTaskNotify(hCloud, EV_STATE, eSetBits);
}

BinState snapshot() {
  xSemaphoreTake(stLock, portMAX_DELAY);
  BinState s = st;
  xSemaphoreGive(stLock);
  return s;
}
```

### 4.2 The interrupt handlers

Every ISR does the least possible: take a timestamp, set a bit, wake a task.

```cpp
constexpr int PIN_PIR = 27, PIN_TOUCH_IRQ = 36, PIN_IMU_INT = 34;
constexpr int PIN_TRIG = 5, PIN_ECHO = 18;

void IRAM_ATTR isrPir() {
  BaseType_t woke = pdFALSE;
  xTaskNotifyFromISR(hControl, EV_PIR, eSetBits, &woke);
  portYIELD_FROM_ISR(woke);
}

void IRAM_ATTR isrTouch() {
  BaseType_t woke = pdFALSE;
  xTaskNotifyFromISR(hUi, EV_TOUCH, eSetBits, &woke);
  portYIELD_FROM_ISR(woke);
}

void IRAM_ATTR isrImu() {
  BaseType_t woke = pdFALSE;
  xTaskNotifyFromISR(hSensor, EV_IMU, eSetBits, &woke);
  portYIELD_FROM_ISR(woke);
}

volatile int64_t echoStart = 0, echoUs = 0;

void IRAM_ATTR isrEcho() {
  int64_t now = esp_timer_get_time();                 // safe in an ISR
  if ((REG_READ(GPIO_IN_REG) >> PIN_ECHO) & 1) {      // rising edge
    echoStart = now;
  } else {                                            // falling edge
    echoUs = now - echoStart;
    BaseType_t woke = pdFALSE;
    xTaskNotifyFromISR(hSensor, EV_ECHO, eSetBits, &woke);
    portYIELD_FROM_ISR(woke);
  }
}
```

`REG_READ(GPIO_IN_REG)` reads all pins below 32 in one go and is safe inside an ISR. Integer maths only: no `float` in an ISR on the ESP32.

### 4.3 controlTask: lid, lock and commands

The lid timeout is just the wait time, so no extra timer object is needed.

```cpp
void controlTask(void*) {
  int64_t closeAt = 0;                                 // 0 = no close pending
  for (;;) {
    TickType_t wait = portMAX_DELAY;
    if (closeAt) {
      int64_t ms = (closeAt - esp_timer_get_time()) / 1000;
      wait = ms > 0 ? pdMS_TO_TICKS(ms) : 0;
    }

    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, wait);

    Command c;
    while (xQueueReceive(cmdQueue, &c, 0) == pdTRUE) runCommand(c);   // LOCK, COLLECT, ...

    if (ev & EV_PIR) {
      bool motion = digitalRead(PIN_PIR);              // fine here, this is a task
      xSemaphoreTake(stLock, portMAX_DELAY);
      st.motion = motion; st.version++;
      bool canOpen = (st.status == 0);                 // not LOCKED, TAMPER, ...
      xSemaphoreGive(stLock);
      if (motion && canOpen) { openLid(); closeAt = esp_timer_get_time() + cal.pir_hold_s * 1000000LL; }
      if (motion && !canOpen) buzz(TUNE_LOCKED_WAVE);
      publish();
    }

    if (closeAt && esp_timer_get_time() >= closeAt) {
      if (digitalRead(PIN_PIR)) closeAt = esp_timer_get_time() + cal.pir_hold_s * 1000000LL;  // still a hand
      else { closeLid(); closeAt = 0; publish(); }
    }
  }
}
```

### 4.4 sensorTask: echo, IMU, load cell, BME680

```cpp
void sensorTask(void*) {
  TickType_t lastTrig = 0, lastEnv = 0, lastImuCheck = 0;
  bool envPending = false;
  uint32_t envReadyAt = 0;

  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(100));   // 100 ms tick at most
    TickType_t now = xTaskGetTickCount();
    bool changed = false;

    // Ultrasonic: fire a trigger every 500 ms; the ISR reports the echo.
    if (now - lastTrig >= pdMS_TO_TICKS(500)) {
      lastTrig = now;
      digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10); digitalWrite(PIN_TRIG, LOW);
    }
    if (ev & EV_ECHO) changed |= updateFill(echoUs);   // median of 5, cm = us / 58

    // BMI160: the interrupt means "moved"; also check once a second in case a slow tilt was missed.
    if ((ev & EV_IMU) || now - lastImuCheck >= pdMS_TO_TICKS(1000)) {
      lastImuCheck = now;
      changed |= updateTilt();                           // reads accel over I2C, applies tilt_th
    }

    // HX711: 10 readings a second at most, read only when ready.
    if (scale.is_ready()) changed |= updateWeight(scale.read());

    // BME680: start, then collect when the heater is done. Never blocks.
    if (!envPending && now - lastEnv >= pdMS_TO_TICKS(3000)) {
      envReadyAt = bme.beginReading();                   // returns the millis() when ready
      envPending = envReadyAt != 0; lastEnv = now;
    }
    if (envPending && millis() >= envReadyAt) {
      envPending = false;
      if (bme.endReading()) changed |= updateEnv();
    }

    if (changed) publish();   // the update*() helpers take stLock and bump st.version
  }
}
```

Each `update*()` returns `true` only when a value moves by more than the small steps in integration guide 6A (fill ±1 %, weight ±20 g, and so on). That one rule keeps the screen, the cloud and the dashboard from redrawing or sending for noise.

### 4.5 uiTask: screen and touch

```cpp
XPT2046_Touchscreen ts(15);   // CS 15. Leave the IRQ pin out here: the library would attach
                               // its own ISR, and ours (section 4.7) would replace it.

void uiTask(void*) {
  uint32_t lastDrawn = 0;
  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(1000));   // 1 s for the clock and timeouts

    if ((ev & EV_TOUCH) && ts.touched()) handleTouch(ts.getPoint());   // confirm, see section 6

    BinState s = snapshot();
    if (s.version != lastDrawn) { drawStatus(s); lastDrawn = s.version; }
    tickScreenTimeouts();                      // the 30 s return-to-main rule
  }
}
```

### 4.6 cloudTask: send at once on change, otherwise on its timers

```cpp
void cloudTask(void*) {
  bool pushPending = false;
  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(pushPending ? 250 : 1000));

    if ((ev & EV_STATE) || pushPending) pushPending = pushEventsToDashboards();   // SSE, section 5.1
    if (WiFi.isConnected()) {
      firebaseStep(ev & EV_STATE);    // writes at once on an important change, else 5 s / 60 s (6A)
      thingSpeakStep();               // 30 s pace, 15 s minimum, early for alerts (6A)
      pollCommands();                 // Firebase 2 s / 10 s, TalkBack 5 s; results go to cmdQueue
    }
  }
}
```

### 4.7 setup()

```cpp
void setup() {
  Serial.begin(115200);
  stLock = xSemaphoreCreateMutex();
  cmdQueue = xQueueCreate(8, sizeof(Command));

  initSensors(); initDisplay(); initServo(); initBuzzer();   // before any task starts
  configureBmi160AnyMotion();                                // section 4.8, before attaching its ISR

  // ui and cloud first: control and sensor call publish(), which notifies them.
  xTaskCreatePinnedToCore(uiTask,      "ui",      8192, nullptr, 2, &hUi,      1);
  xTaskCreatePinnedToCore(cloudTask,   "cloud",  10240, nullptr, 1, &hCloud,   0);
  xTaskCreatePinnedToCore(controlTask, "control", 4096, nullptr, 4, &hControl, 1);
  xTaskCreatePinnedToCore(sensorTask,  "sensor",  4096, nullptr, 3, &hSensor,  1);

  // Attach ISRs only after the task handles exist, or the first edge notifies a null task.
  pinMode(PIN_PIR, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_PIR), isrPir, CHANGE);
  pinMode(PIN_TOUCH_IRQ, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_TOUCH_IRQ), isrTouch, FALLING);
  pinMode(PIN_ECHO, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_ECHO), isrEcho, CHANGE);
  pinMode(PIN_IMU_INT, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_IMU_INT), isrImu, RISING);
  pinMode(PIN_TRIG, OUTPUT);

  WiFi.onEvent(onWifiEvent);           // ARDUINO_EVENT_WIFI_STA_GOT_IP / _DISCONNECTED
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  startWebServer();                    // section 5.1
}

void loop() { vTaskDelete(nullptr); }  // nothing left for loop() to do
```

### 4.8 BMI160 any-motion interrupt (optional wire to GPIO 34)

Wire BMI160 **INT1 to GPIO 34**. Then, over I2C, enable the any-motion interrupt on INT1 as a push-pull, active-high output. The registers involved (check each value against the BMI160 datasheet's register map before you trust it):

| Register | Address | What to set |
|---|---|---|
| `INT_MOTION[1]` | 0x60 | Any-motion threshold (start low, tune on the bench) |
| `INT_MOTION[0]` | 0x5F | How many samples above the threshold count as motion |
| `INT_OUT_CTRL` | 0x53 | INT1 output on, push-pull, active high |
| `INT_MAP[0]` | 0x55 | Map any-motion to INT1 |
| `INT_EN[0]` | 0x50 | Enable any-motion on X, Y and Z |

GPIO 34 has no internal pull-up or pull-down. Until the chip is configured its INT1 pin may float, so attach the ISR only after `configureBmi160AnyMotion()`, or add a 100 kΩ pull-down. If you use a BMI160 library, it probably has a call for this already.

---

## 5. The dashboard: push instead of polling

Today the dashboard mockup polls each bin with `setInterval(poll, 1000)` (`Mockups/smartbin-dashboard-v12.html`). The event-driven version keeps one open connection per bin and the bin **pushes** a status the moment something changes. The standard browser tool for this is **Server-Sent Events** (`EventSource`): one-way, plain HTTP, automatic reconnect, and ESPAsyncWebServer supports it.

### 5.1 ESP32 side: add `GET /api/events`

This is an **addition**. `GET /api/status` stays exactly as it is for the Android app and as the dashboard's fallback.

```cpp
AsyncWebServer server(80);
AsyncEventSource events("/api/events");

void startWebServer() {
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");   // integration guide 4.5
  events.onConnect([](AsyncEventSourceClient* c) {
    c->send(statusJson().c_str(), "status", millis(), 3000);   // full status at once; retry after 3 s
  });
  server.addHandler(&events);
  // ... /api/status, /api/command, /api/calibrate, /api/simulate as before ...
  server.begin();
}

// Called from cloudTask only, so one task owns the sends.
// Returns true when a change is still waiting, so cloudTask checks back in 250 ms.
bool pushEventsToDashboards() {
  static uint32_t lastSent = 0, lastPush = 0;
  BinState s = snapshot();
  if (s.version == lastSent) return false;
  if (millis() - lastPush < 250) return true;          // at most 4 pushes a second
  events.send(statusJson(s).c_str(), "status", millis());
  lastSent = s.version; lastPush = millis();
  return false;
}
```

Also send a small `ping` event every 10 s (`events.send("1", "ping", millis())`) from `cloudTask`, so the dashboard can tell a quiet bin from a dead one. Check in the browser's developer tools that `/api/events` carries the `Access-Control-Allow-Origin` header; without it, a dashboard served from another address can't open the stream.

Limits worth knowing: each open dashboard is one TCP connection on the bin. Three or four at once is fine; dozens are not. `POST /api/command` stays a normal request; after it, the new status arrives through the stream, so the confirm in integration guide section 7 becomes almost instant.

### 5.2 Browser side

```js
// One watcher per bin. Pushes when it can, falls back to the old 1 s poll when it can't.
function watchBin(bin, render, markDown) {
  let es = null, pollTimer = null, lastSeen = 0;

  const seen = () => { lastSeen = Date.now(); };
  const startPoll = () => {
    if (pollTimer) return;
    pollTimer = setInterval(async () => {
      try {
        const r = await fetch(`http://${bin.ip}/api/status`, { signal: AbortSignal.timeout(1500) });
        render(bin, await r.json()); seen();
      } catch { /* the stale check below handles it */ }
    }, 1000);
  };
  const stopPoll = () => { clearInterval(pollTimer); pollTimer = null; };

  function open() {
    es = new EventSource(`http://${bin.ip}/api/events`);
    es.addEventListener('status', e => { seen(); stopPoll(); render(bin, JSON.parse(e.data)); });
    es.addEventListener('ping', seen);
    es.onerror = () => startPoll();          // EventSource keeps retrying by itself meanwhile
  }

  const stale = setInterval(() => {
    if (!document.hidden && Date.now() - lastSeen > 15000) markDown(bin);
  }, 5000);

  // Stop listening while the tab is hidden; resume with a fresh status when it comes back.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { es?.close(); es = null; stopPoll(); }
    else if (!es) open();
  });

  open();
  return () => { es?.close(); stopPoll(); clearInterval(stale); };
}
```

`render()` should redraw only the parts whose values changed, the same rule as the firmware's small steps. With pushes arriving at most 4 times a second there is no need for animation-frame tricks.

### 5.3 Firebase and ThingSpeak in the dashboard

- **Firebase** can push too. Its REST API speaks the same `EventSource` protocol: open `https://<db>/sb/bins.json?auth=<idToken>` and listen for `put` and `patch` events (`{"path": ..., "data": ...}`), plus `keep-alive`, `cancel` and `auth_revoked`. The sign-in token expires every hour, so on `auth_revoked` close the stream, refresh the token and open it again. The Firebase JS SDK's `onValue()` does all of this for you on an HTTPS page (integration guide section 2, option C). Either way it replaces the 5 s Firebase poll.
- **ThingSpeak** has no push over its HTTP API, so keep reading `feeds/last.json` every 15 s. It is history, not live data, so that's fine.
- **Mixed content:** an `https://` page can't open `http://<bin-ip>/api/events`. A hosted dashboard (option C) uses Firebase streaming only.

---

## 6. Pitfalls specific to this board

1. **Keep ISRs tiny and `IRAM_ATTR`.** No `Serial`, `delay()`, `Wire`, `SPI`, `WiFi`, HTTP, `String`, `new`, `malloc`, or `float` inside an ISR. Only `...FromISR` FreeRTOS calls. An ISR that isn't in IRAM can crash the board while flash is busy (for example during a `Preferences` save).
2. **GPIO 36 (touch IRQ) can see false edges.** Espressif's ESP32 errata lists short false pulses on GPIO 36 and 39 when some internal analog blocks power on, and Wi-Fi activity is a common trigger in practice. That is why `uiTask` confirms with `ts.touched()` before acting. Don't move the BMI160 interrupt to GPIO 39 for the same reason; use 34.
3. **GPIO 34 to 39 are input-only with no pull resistors.** Anything on them needs a driven signal (push-pull) or an external resistor.
4. **GPIO 12 (buzzer) is a boot strapping pin.** If it is pulled high at power-on, the ESP32 picks the wrong flash voltage and won't boot. So **don't** add the 10 kΩ pull-up mentioned in integration guide 9.2 on this pin. A passive buzzer driven through a transistor is fine. GPIO 5 (trig) and 15 (touch CS) are strapping pins too, but they behave as the plan describes.
5. **One owner per bus.** Two tasks using `Wire` at once corrupt each other's transfers. The same goes for the TFT and touch on SPI. Section 3's ownership rules avoid this without extra locks.
6. **Keep the mutex short.** Take `stLock`, copy or change a few fields, give it back. Never hold it across I2C, SPI, HTTP or `delay()`.
7. **Async web handlers must return fast.** They run in the AsyncTCP task. For `/api/command`, check the command, put it in `cmdQueue`, and reply with the current status. Never read a sensor or call the cloud from a handler.
8. **Watch the stacks.** TLS needs room. If a task resets the board with a stack overflow, raise its stack size. `uxTaskGetStackHighWaterMark(nullptr)` printed once a minute shows how close each task gets.
9. **Debouncing.** The PIR doesn't need it (its hold time does the job). Touch is confirmed by `ts.touched()`. If you ever add a push button, ignore edges within 30 to 50 ms of the last one, using the time from `esp_timer_get_time()`.
10. **Create tasks in the right order and attach ISRs last** (section 4.7). Notifying a task whose handle is still empty crashes the board.

---

## 7. Testing without the hardware

`Android App/tools/smartbin_mock_server.py` still works: it doesn't serve `/api/events` yet, so the dashboard falls back to polling against it, which also tests the fallback path. Test the push path on a real ESP32 with nothing but the Wi-Fi and a fake reading that changes every few seconds.

On the ESP32, test one interrupt at a time with a counter: increment it in the task the ISR wakes, and print the count once a second from that task. Wave at the PIR, tap the screen, tilt the bin, and watch the counts.

---

## 8. Suggested order of work

1. **Non-blocking first.** Get every sensor working with `millis()` timers in `loop()`, with `pulseIn(PIN_ECHO, HIGH, 25000)`, `beginReading()`/`endReading()` for the BME680, and `is_ready()` for the HX711. No `delay()` anywhere. This alone fixes most slowness.
2. **Move the cloud into `cloudTask`** on core 0. Now a slow Firebase or ThingSpeak call can't freeze the lid or the screen.
3. **Split the rest into tasks** (section 3) and add `BinState` with its mutex.
4. **Add the interrupts one at a time:** PIR, then touch, then the echo, then the BMI160 if you add the wire. Test each before the next.
5. **Add `/api/events`** and switch the dashboard to `watchBin()`. Keep the polling fallback.
6. **Firebase streaming in the dashboard**, last and optional.

---

## 9. Decisions to send back to Brian

1. Do you add `GET /api/events` (SSE)? The app can switch to it later; until then it keeps polling `/api/status`, so nothing breaks either way.
2. Do you wire BMI160 INT1 to GPIO 34? If yes, the pin table and schematic need that one line.
3. HC-SR04: echo interrupt or `pulseIn()` with a timeout?
4. Dashboard Firebase reading: REST `EventSource` or the JS SDK?
