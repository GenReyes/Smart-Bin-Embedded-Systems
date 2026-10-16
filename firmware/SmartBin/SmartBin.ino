// Smart-Bin ESP32 firmware. Group 2, BET-CpET 4A-M, TUP Manila.
// Before uploading: copy secrets.example.h to secrets.h and fill it in,
// and set BIN_NUMBER in config.h (1 for Bin A, 2 for Bin B).

#include "config.h"
#include "state.h"
#include "sensors.h"
#include "control.h"
#include "buzzer.h"
#include "net.h"
#include "cloud.h"
#include "ui.h"

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("\nSmart-Bin %s firmware v%s\n", BIN_ID, FW_VERSION);

  stateInit();
  uiInit();
  buzzerInit();
  controlInit();
  sensorsInit();
  netInit();

  xTaskCreatePinnedToCore(uiTask, "ui", 8192, nullptr, 2, &hUi, 1);
  xTaskCreatePinnedToCore(cloudTask, "cloud", 12288, nullptr, 1, &hCloud, 0);
  xTaskCreatePinnedToCore(pushTask, "push", 4096, nullptr, 2, &hPush, 0);
  xTaskCreatePinnedToCore(controlTask, "control", 4096, nullptr, 4, &hControl, 1);
  xTaskCreatePinnedToCore(sensorTask, "sensor", 4096, nullptr, 3, &hSensor, 1);

  attachInterrupt(digitalPinToInterrupt(PIN_PIR), isrPir, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_TOUCH_IRQ), isrTouch, FALLING);
#if USE_IMU_INT
  pinMode(PIN_IMU_INT, INPUT);
#endif

  startWebServer();
  wifiConnect();
}

void loop() { vTaskDelete(nullptr); }
