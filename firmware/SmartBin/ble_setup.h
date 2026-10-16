#pragma once
#include <Arduino.h>

enum BtPhase : uint8_t { BT_IDLE = 0, BT_WAIT, BT_CODE, BT_LOCKOUT, BT_PAIRED, BT_CONN, BT_OK, BT_FAIL, BT_OFF };

struct BtView {
  BtPhase phase;
  uint32_t code;
  uint32_t phaseMs;      // millis() when the phase began
  uint32_t offAtMs;      // millis() when Bluetooth switches itself off
  char ssid[33];
  char why[40];
  char ip[16];
  uint8_t netCount;
  char nets[3][33];
  int8_t rssi[3];
};

void bleStart();
void bleStop(bool fromRemote);
BtView bleView();
void bleRetry();
