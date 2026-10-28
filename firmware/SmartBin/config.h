#pragma once
#include <Arduino.h>

// Which bin this board is: 1 = Bin A (bin01), 2 = Bin B (bin02).
#ifndef BIN_NUMBER
#define BIN_NUMBER 1
#endif

#define FW_VERSION "1.0.0"

// Pins, from the Implementation Plan v2 pin table.
constexpr int PIN_TRIG = 5;
constexpr int PIN_ECHO = 18;
constexpr int PIN_BUZZER = 4;
constexpr int PIN_SERVO = 13;
constexpr int PIN_HX_SCK = 14;
constexpr int PIN_HX_DT = 32;
constexpr int PIN_TOUCH_CS = 15;
constexpr int PIN_TFT_DC = 16;
constexpr int PIN_TFT_CS = 17;
constexpr int PIN_SPI_MOSI = 19;
constexpr int PIN_SPI_SCK = 23;
constexpr int PIN_SPI_MISO = 35;
constexpr int PIN_TOUCH_IRQ = 36;
constexpr int PIN_SDA = 21;
constexpr int PIN_SCL = 22;
constexpr int PIN_LED_R = 25;
constexpr int PIN_LED_G = 26;
constexpr int PIN_LED_B = 33;
constexpr int PIN_PIR = 27;
constexpr int PIN_IMU_INT = 34;     // optional BMI160 INT1 wire; set USE_IMU_INT to 1 once it is wired

#define USE_IMU_INT 0
#define BUZZER_ACTIVE_LOW 0          // 1 for "low level trigger" modules
#define LED_ACTIVE_LOW 0             // 1 for a common-anode RGB LED

// Touch calibration (raw XPT2046 values at the screen edges). Print raw values
// with TOUCH_DEBUG 1, press the four corners, and copy the numbers here.
#define TOUCH_DEBUG 0
constexpr int TS_RAW_X0 = 3800, TS_RAW_X1 = 300;   // raw x at screen x = 0 and x = 239
constexpr int TS_RAW_Y0 = 3800, TS_RAW_Y1 = 300;   // raw y at screen y = 0 and y = 319
#define TS_SWAP_XY 0
constexpr uint8_t TFT_ROTATION = 2;                // 0 = portrait 240 x 320

// Timing (ESP32 Integration Guide, sections 3 and 6A).
constexpr uint32_t FB_WATCHED_MS = 5000;
constexpr uint32_t FB_IDLE_MS = 60000;
constexpr uint32_t FB_CMD_WATCHED_MS = 2000;
constexpr uint32_t FB_CMD_IDLE_MS = 10000;
constexpr uint32_t FB_WATCHERS_MS = 30000;
constexpr uint32_t FB_USAGE_MS = 600000;
constexpr uint32_t TS_WRITE_MS = 30000;
constexpr uint32_t TS_MIN_GAP_MS = 15500;
constexpr uint32_t TS_HEARTBEAT_MS = 300000;
constexpr uint32_t TB_WATCHED_MS = 5000;
constexpr uint32_t TB_IDLE_MS = 15000;
constexpr uint32_t CMD_MAX_AGE_MS = 60000;
constexpr uint32_t TAMPER_COUNTDOWN_S = 10;
constexpr uint32_t TAMPER_ALARM_EVERY_MS = 2000;   // latched tamper siren repeats this often
constexpr uint32_t SCREEN_TIMEOUT_MS = 30000;
constexpr uint32_t BT_IDLE_OFF_MS = 300000;
// 1: normal boots give the Bluetooth memory back to the heap, and the bin restarts into
//    Wi-Fi setup. 0: that memory is kept from power-on, so setup starts without a restart,
//    but on the bench Firebase then failed its handshakes (BIGNUM alloc, largest block 34 KB).
// Either way the bin restarts once when setup ends, for a clean heap.
#define BT_RESTART_FOR_MEMORY 1
constexpr uint32_t BT_CODE_MS = 60000;
constexpr uint32_t BT_LOCKOUT_MS = 60000;
constexpr uint64_t FB_MONTH_LIMIT = 10737418240ULL;

// 0 = always use the fast "someone is watching" pace. Set to 1 once the app writes
// /sb/watchers (Integration Guide 6A); the dashboard already does.
#define FB_REQUIRE_WATCHERS 0

#define BT_SVC_UUID  "b1a70001-5c2e-4f8a-9d33-0a9c2b6f17c1"
#define BT_SSID_UUID "b1a70002-5c2e-4f8a-9d33-0a9c2b6f17c1"
#define BT_PASS_UUID "b1a70003-5c2e-4f8a-9d33-0a9c2b6f17c1"
#define BT_CTRL_UUID "b1a70004-5c2e-4f8a-9d33-0a9c2b6f17c1"
#define BT_STAT_UUID "b1a70005-5c2e-4f8a-9d33-0a9c2b6f17c1"
#define BT_SCAN_UUID "b1a70006-5c2e-4f8a-9d33-0a9c2b6f17c1"

#if BIN_NUMBER == 1
#define BIN_ID "bin01"
#define BIN_LABEL "BIN 01"
#define BIN_HOST "smartbin-01"
#define BIN_BLE_NAME "SmartBin-01"
#else
#define BIN_ID "bin02"
#define BIN_LABEL "BIN 02"
#define BIN_HOST "smartbin-02"
#define BIN_BLE_NAME "SmartBin-02"
#endif
