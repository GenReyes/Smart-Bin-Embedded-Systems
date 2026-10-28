#include <Arduino.h>
#include <nvs.h>
#include "esp32-hal-bt.h"
#include "btmem.h"
#include "config.h"

static bool flagAtBoot = false;

static uint8_t readFlag() {
  nvs_handle_t h;
  uint8_t v = 0;
  if (nvs_open("smartbin", NVS_READONLY, &h) == ESP_OK) {
    nvs_get_u8(h, "btboot", &v);
    nvs_close(h);
  }
  return v;
}

static void writeFlag(uint8_t v) {
  nvs_handle_t h;
  if (nvs_open("smartbin", NVS_READWRITE, &h) == ESP_OK) {
    nvs_set_u8(h, "btboot", v);
    nvs_commit(h);
    nvs_close(h);
  }
}

// The core calls this before setup(). With BT_RESTART_FOR_MEMORY, Bluetooth memory is kept
// only on the boot started for Wi-Fi setup; every other boot gives it to the heap.
extern "C" bool bleInUse(void) {
#if BT_RESTART_FOR_MEMORY
  flagAtBoot = readFlag() == 1;
  return flagAtBoot;
#else
  return true;
#endif
}

bool btBootRequested() { return flagAtBoot; }

bool btNeedsRestart() { return btMemReleased(BT_MODE_BLE); }

bool btBootMode() { return flagAtBoot && !btMemReleased(BT_MODE_BLE); }

void btRestartForBle() {
  writeFlag(1);
  Serial.println("[bt] restarting to keep memory for Bluetooth");
  Serial.flush();
  delay(300);
  ESP.restart();
}

void btClearBootFlag() {
  if (readFlag() != 0) writeFlag(0);
}
