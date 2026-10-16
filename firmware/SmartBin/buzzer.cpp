#include "buzzer.h"
#include "state.h"

struct Note { uint16_t hz, ms, hzEnd; };
struct TuneDef { const char* name; const Note* n; uint8_t len; uint8_t pri; uint8_t kind; };
enum : uint8_t { K_UI = 0, K_LID, K_EVENT, K_CAL, K_ALARM };

static const Note n_key[] = {{2400, 15}};
static const Note n_pin_key[] = {{2600, 12}};
static const Note n_pin_del[] = {{2000, 15}};
static const Note n_cancel[] = {{2300, 30}, {1800, 45}};
static const Note n_theme_light[] = {{2300, 35}, {2700, 50}};
static const Note n_theme_dark[] = {{2000, 35}, {1700, 50}};
static const Note n_open[] = {{1800, 30}, {2300, 40}};
static const Note n_pin_ok[] = {{1600, 60}, {1900, 60}, {2200, 60}, {1900, 60}, {2200, 120}};
static const Note n_pin_wrong[] = {{1700, 60}, {0, 30}, {1700, 60}, {0, 30}, {1500, 150}};
static const Note n_done[] = {{1800, 70}, {2100, 70}, {2400, 70}, {2800, 200}};
static const Note n_boot_ok[] = {{1600, 70}, {1900, 70}, {2200, 70}, {2800, 180}};
static const Note n_boot_fault[] = {{1600, 70}, {1900, 70}, {0, 40}, {1700, 100}, {0, 60}, {1500, 210}};
static const Note n_lid_open[] = {{1800, 25}, {2200, 30}};
static const Note n_lid_close[] = {{2200, 25}, {1800, 30}};
static const Note n_locked_wave[] = {{1600, 60}, {0, 40}, {1500, 120}};
static const Note n_lock[] = {{2000, 40}, {0, 20}, {1600, 120}};
static const Note n_unlock[] = {{1600, 40}, {0, 20}, {2000, 120}};
static const Note n_cal_start[] = {{2300, 40}, {0, 50}, {2300, 40}, {0, 50}, {2300, 40}, {0, 50}, {2700, 120}};
static const Note n_cal_step[] = {{2400, 40}};
static const Note n_saved[] = {{2200, 60}, {2600, 100}};
static const Note n_sim_on[] = {{1600, 60}, {1800, 60}, {2000, 60}, {2300, 140}};
static const Note n_sim_off[] = {{2300, 60}, {2000, 60}, {1800, 60}, {1600, 140}};
static const Note n_cal_cancel[] = {{2400, 40}, {0, 40}, {1900, 120}};
static const Note n_air_ready[] = {{2000, 40}, {2400, 70}};
static const Note n_back_main[] = {{2400, 30}, {2000, 50}};
static const Note n_full[] = {{2000, 180}, {0, 80}, {2000, 180}, {0, 80}, {2000, 180}, {0, 200}, {2600, 420}};
static const Note n_tamper_tick[] = {{2000, 60}};
static const Note n_tamper_tick_end[] = {{2400, 60}};
static const Note n_tamper[] = {{1600, 340, 2800}, {2800, 340, 1600}, {1600, 340, 2800}, {2800, 340, 1600}};
static const Note n_env_over[] = {{2400, 120}, {1900, 120}, {2400, 120}, {1900, 260}};
static const Note n_sensor_lost[] = {{1900, 100}, {0, 60}, {1500, 210}};
static const Note n_tamper_settled[] = {{1900, 60}, {2300, 60}, {2600, 120}};
static const Note n_env_ok[] = {{2600, 50}, {2200, 100}};
static const Note n_sensor_back[] = {{1500, 80}, {2000, 150}};
static const Note n_wifi_up[] = {{1800, 70}, {2200, 70}, {2700, 150}};
static const Note n_wifi_lost[] = {{2700, 90}, {2200, 90}, {1800, 190}};
static const Note n_cloud_down[] = {{2200, 60}, {0, 30}, {2400, 60}, {0, 30}, {1900, 130}};
static const Note n_cloud_up[] = {{1900, 60}, {0, 30}, {2400, 60}, {0, 30}, {2700, 120}};
static const Note n_cmd_fail[] = {{1900, 80}, {0, 30}, {1700, 80}, {0, 30}, {1500, 200}};
static const Note n_bt_wait[] = {{1800, 30}, {0, 30}, {1800, 30}, {2300, 60}};
static const Note n_bt_code[] = {{2400, 30}, {0, 60}, {2400, 30}};
static const Note n_bt_paired[] = {{2300, 40}, {2800, 40}, {2300, 40}, {2800, 140}};
static const Note n_bt_wrong[] = {{1800, 50}, {0, 25}, {1800, 50}, {1500, 140}};
static const Note n_bt_stopped[] = {{2200, 180}, {0, 50}, {1500, 320}};
static const Note n_bt_joined[] = {{1700, 60}, {2000, 60}, {2400, 60}, {2800, 180}};
static const Note n_bt_join_fail[] = {{1600, 70}, {0, 40}, {1500, 120}};
static const Note n_bt_off[] = {{2300, 50}, {1800, 50}, {0, 25}, {1500, 140}};
static const Note n_bt_auto_off[] = {{2300, 50}, {1800, 50}, {0, 25}, {1500, 60}, {0, 60}, {1500, 60}};

static const TuneDef TUNES[] = {
  {"key", n_key, sizeof(n_key) / sizeof(Note), 0, K_UI},
  {"pin_key", n_pin_key, sizeof(n_pin_key) / sizeof(Note), 0, K_UI},
  {"pin_del", n_pin_del, sizeof(n_pin_del) / sizeof(Note), 0, K_UI},
  {"cancel", n_cancel, sizeof(n_cancel) / sizeof(Note), 0, K_UI},
  {"theme_light", n_theme_light, sizeof(n_theme_light) / sizeof(Note), 0, K_UI},
  {"theme_dark", n_theme_dark, sizeof(n_theme_dark) / sizeof(Note), 0, K_UI},
  {"open", n_open, sizeof(n_open) / sizeof(Note), 0, K_UI},
  {"pin_ok", n_pin_ok, sizeof(n_pin_ok) / sizeof(Note), 3, K_EVENT},
  {"pin_wrong", n_pin_wrong, sizeof(n_pin_wrong) / sizeof(Note), 3, K_EVENT},
  {"done", n_done, sizeof(n_done) / sizeof(Note), 3, K_EVENT},
  {"boot_ok", n_boot_ok, sizeof(n_boot_ok) / sizeof(Note), 1, K_EVENT},
  {"boot_fault", n_boot_fault, sizeof(n_boot_fault) / sizeof(Note), 4, K_EVENT},
  {"lid_open", n_lid_open, sizeof(n_lid_open) / sizeof(Note), 0, K_LID},
  {"lid_close", n_lid_close, sizeof(n_lid_close) / sizeof(Note), 0, K_LID},
  {"locked_wave", n_locked_wave, sizeof(n_locked_wave) / sizeof(Note), 2, K_EVENT},
  {"lock", n_lock, sizeof(n_lock) / sizeof(Note), 3, K_EVENT},
  {"unlock", n_unlock, sizeof(n_unlock) / sizeof(Note), 3, K_EVENT},
  {"cal_start", n_cal_start, sizeof(n_cal_start) / sizeof(Note), 2, K_CAL},
  {"cal_step", n_cal_step, sizeof(n_cal_step) / sizeof(Note), 1, K_CAL},
  {"saved", n_saved, sizeof(n_saved) / sizeof(Note), 2, K_CAL},
  {"sim_on", n_sim_on, sizeof(n_sim_on) / sizeof(Note), 2, K_EVENT},
  {"sim_off", n_sim_off, sizeof(n_sim_off) / sizeof(Note), 2, K_EVENT},
  {"cal_cancel", n_cal_cancel, sizeof(n_cal_cancel) / sizeof(Note), 2, K_CAL},
  {"air_ready", n_air_ready, sizeof(n_air_ready) / sizeof(Note), 1, K_EVENT},
  {"back_main", n_back_main, sizeof(n_back_main) / sizeof(Note), 1, K_EVENT},
  {"full", n_full, sizeof(n_full) / sizeof(Note), 5, K_ALARM},
  {"tamper_tick", n_tamper_tick, sizeof(n_tamper_tick) / sizeof(Note), 5, K_ALARM},
  {"tamper_tick_end", n_tamper_tick_end, sizeof(n_tamper_tick_end) / sizeof(Note), 5, K_ALARM},
  {"tamper", n_tamper, sizeof(n_tamper) / sizeof(Note), 5, K_ALARM},
  {"env_over", n_env_over, sizeof(n_env_over) / sizeof(Note), 4, K_ALARM},
  {"sensor_lost", n_sensor_lost, sizeof(n_sensor_lost) / sizeof(Note), 4, K_EVENT},
  {"tamper_settled", n_tamper_settled, sizeof(n_tamper_settled) / sizeof(Note), 4, K_EVENT},
  {"env_ok", n_env_ok, sizeof(n_env_ok) / sizeof(Note), 2, K_EVENT},
  {"sensor_back", n_sensor_back, sizeof(n_sensor_back) / sizeof(Note), 3, K_EVENT},
  {"wifi_up", n_wifi_up, sizeof(n_wifi_up) / sizeof(Note), 2, K_EVENT},
  {"wifi_lost", n_wifi_lost, sizeof(n_wifi_lost) / sizeof(Note), 3, K_EVENT},
  {"cloud_down", n_cloud_down, sizeof(n_cloud_down) / sizeof(Note), 2, K_EVENT},
  {"cloud_up", n_cloud_up, sizeof(n_cloud_up) / sizeof(Note), 2, K_EVENT},
  {"cmd_fail", n_cmd_fail, sizeof(n_cmd_fail) / sizeof(Note), 3, K_EVENT},
  {"bt_wait", n_bt_wait, sizeof(n_bt_wait) / sizeof(Note), 1, K_EVENT},
  {"bt_code", n_bt_code, sizeof(n_bt_code) / sizeof(Note), 2, K_EVENT},
  {"bt_paired", n_bt_paired, sizeof(n_bt_paired) / sizeof(Note), 3, K_EVENT},
  {"bt_wrong", n_bt_wrong, sizeof(n_bt_wrong) / sizeof(Note), 3, K_EVENT},
  {"bt_stopped", n_bt_stopped, sizeof(n_bt_stopped) / sizeof(Note), 4, K_EVENT},
  {"bt_joined", n_bt_joined, sizeof(n_bt_joined) / sizeof(Note), 3, K_EVENT},
  {"bt_join_fail", n_bt_join_fail, sizeof(n_bt_join_fail) / sizeof(Note), 3, K_EVENT},
  {"bt_off", n_bt_off, sizeof(n_bt_off) / sizeof(Note), 2, K_EVENT},
  {"bt_auto_off", n_bt_auto_off, sizeof(n_bt_auto_off) / sizeof(Note), 1, K_EVENT},
};

static_assert(sizeof(TUNES) / sizeof(TUNES[0]) == T_COUNT, "tune table and enum differ");

static QueueHandle_t q;
static volatile uint8_t playing = 0xFF;
static constexpr uint8_t CH = 6, RES = 10;

static uint16_t duty() {
  uint8_t v = calSnapshot().buzz_vol;
  uint16_t d = v >= 3 ? 512 : v == 2 ? 256 : 100;
  return BUZZER_ACTIVE_LOW ? (1023 - d) : d;
}

static void silence() {
  ledcWriteTone(PIN_BUZZER, 0);
  ledcWrite(PIN_BUZZER, BUZZER_ACTIVE_LOW ? 1023 : 0);
}

static void tone(uint16_t hz) {
  ledcWriteTone(PIN_BUZZER, hz);
  ledcWrite(PIN_BUZZER, duty());
}

static bool quietNow(const Cal& c) {
  int m = localMinuteOfDay();
  if (!c.quiet_on || m < 0) return false;
  return c.quiet_from <= c.quiet_to ? (m >= c.quiet_from && m < c.quiet_to) : (m >= c.quiet_from || m < c.quiet_to);
}

static void buzzerTask(void*) {
  uint8_t id;
  for (;;) {
    if (xQueueReceive(q, &id, portMAX_DELAY) != pdTRUE) continue;
    const TuneDef& t = TUNES[id];
    playing = t.pri;
    for (uint8_t i = 0; i < t.len; i++) {
      if (uxQueueMessagesWaiting(q)) break;
      const Note& n = t.n[i];
      if (!n.hz) {
        silence();
        vTaskDelay(pdMS_TO_TICKS(n.ms));
      } else if (n.hzEnd) {
        for (uint16_t s = 0; s < n.ms; s += 10) {
          tone(n.hz + (int32_t)(n.hzEnd - n.hz) * s / n.ms);
          vTaskDelay(pdMS_TO_TICKS(10));
        }
      } else {
        tone(n.hz);
        vTaskDelay(pdMS_TO_TICKS(n.ms));
      }
    }
    silence();
    playing = 0xFF;
  }
}

void buzzerInit() {
  ledcAttachChannel(PIN_BUZZER, 2000, RES, CH);
  silence();
  q = xQueueCreate(1, sizeof(uint8_t));
  xTaskCreatePinnedToCore(buzzerTask, "buzzer", 2048, nullptr, 2, nullptr, 1);
}

void buzz(Tune id) {
  if (id >= T_COUNT || !q) return;
  const TuneDef& t = TUNES[id];
  Cal c = calSnapshot();
  BinState s = snapshot();
  if (!c.buzz_on) return;
  if (t.kind == K_UI && !c.buzz_ui) return;
  if (t.kind == K_LID && !c.buzz_lid) return;
  if (quietNow(c) && t.pri < 5) return;
  if (s.collecting && t.kind == K_ALARM) return;
  if (s.calibrating && t.kind != K_CAL && t.kind != K_UI) return;
  if (playing != 0xFF && t.pri < playing) return;
  uint8_t v = id;
  xQueueOverwrite(q, &v);
}

bool buzzByName(const char* name) {
  for (uint8_t i = 0; i < T_COUNT; i++)
    if (!strcmp(TUNES[i].name, name)) {
      uint8_t v = i;
      if (q) xQueueOverwrite(q, &v);
      return true;
    }
  return false;
}

bool buzzerBusy() { return playing != 0xFF; }
