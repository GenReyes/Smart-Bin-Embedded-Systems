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
#include "esp32-hal-bt.h"
#include "btmem.h"
#include "ble_setup.h"

static void heapNote(const char* where) {
  Serial.printf("[heap] %s: free %u, largest block %u\n", where, (unsigned)ESP.getFreeHeap(),
                (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("\nSmart-Bin %s firmware v%s\n", BIN_ID, FW_VERSION);

  heapNote("start");
  Serial.printf("[bt] ble memory released at boot: %s\n", btMemReleased(BT_MODE_BLE) ? "yes" : "no");
  // setup() runs above the tasks it starts, so they cannot hold it up before the web
  // server and Bluetooth are running.
  vTaskPrioritySet(nullptr, 5);
  stateInit();
  uiInit();
  if (btBootMode()) uiShowBt();
  buzzerInit();
  controlInit();
  sensorsInit();
  netInit();
  heapNote("after init");

  xTaskCreatePinnedToCore(uiTask, "ui", 8192, nullptr, 2, &hUi, 1);
  xTaskCreatePinnedToCore(cloudTask, "cloud", 12288, nullptr, 1, &hCloud, 0);
  xTaskCreatePinnedToCore(pushTask, "push", 4096, nullptr, 2, &hPush, 0);
  xTaskCreatePinnedToCore(controlTask, "control", 4096, nullptr, 4, &hControl, 1);
  xTaskCreatePinnedToCore(sensorTask, "sensor", 4096, nullptr, 3, &hSensor, 1);

  attachInterrupt(digitalPinToInterrupt(PIN_IR), isrIr, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_TOUCH_IRQ), isrTouch, FALLING);
#if USE_IMU_INT
  pinMode(PIN_IMU_INT, INPUT);
#endif

  startWebServer();
  heapNote("after tasks and web server");
  wifiConnect();

  if (btBootRequested()) {
    btClearBootFlag();
    if (btBootMode()) {
      uiShowBt();
      bleStart();
    } else {
      Serial.println("[bt] asked to start Bluetooth, but its memory was not kept; try again");
    }
  }
  vTaskPrioritySet(nullptr, 1);
}

void loop() { vTaskDelete(nullptr); }
