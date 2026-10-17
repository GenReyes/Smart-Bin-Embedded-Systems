#include "ui.h"
#include "state.h"
#include "buzzer.h"
#include "ble_setup.h"
#include <SPI.h>
#include <WiFi.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <XPT2046_Touchscreen.h>
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>
#include <Fonts/FreeSansBold24pt7b.h>

static Adafruit_ILI9341 tft(&SPI, PIN_TFT_DC, PIN_TFT_CS, -1);
static XPT2046_Touchscreen touch(PIN_TOUCH_CS);

void IRAM_ATTR isrTouch() {
  BaseType_t woke = pdFALSE;
  if (hUi) xTaskNotifyFromISR(hUi, EV_TOUCH, eSetBits, &woke);
  portYIELD_FROM_ISR(woke);
}

static constexpr uint16_t C(uint32_t rgb) {
  return (uint16_t)(((rgb >> 8) & 0xF800) | ((rgb >> 5) & 0x07E0) | ((rgb >> 3) & 0x001F));
}

struct Pal {
  uint16_t bg, surf, bd, tx, tx2, g, a, r, p, t, bl, cy, on_g, on_a, on_r, on_p, on_t, on_bl, on_cy, tg, ta, tr, tp, tt, dim, rule;
};
static const Pal LIGHT = {C(0xF5F0EB), C(0xFFFFFF), C(0x1A1A1A), C(0x1A1A1A), C(0x6B635B), C(0x1E8E3E), C(0xD98A1F),
                          C(0xD93025), C(0x7A45B5), C(0x0D8A8A), C(0x1A62C4), C(0x0E7F9E), C(0xFFFFFF), C(0x1A1A1A),
                          C(0xFFFFFF), C(0xFFFFFF), C(0xFFFFFF), C(0xFFFFFF), C(0xFFFFFF), C(0xCADCC8), C(0xEFDCC2),
                          C(0xEFCAC3), C(0xDCCEE0), C(0xC7DCD8), C(0xB5AFA9), C(0xC6C2BE)};
static const Pal DARK = {C(0x2A2520), C(0x3A3530), C(0xE8E0D8), C(0xEFE7DC), C(0xA89E94), C(0x4FBF6E), C(0xE8A94A),
                         C(0xFF6B5E), C(0xB98BE8), C(0x3FC7C2), C(0x79AEF5), C(0x5FD0E8), C(0x13100D), C(0x13100D),
                         C(0x13100D), C(0x13100D), C(0x13100D), C(0x13100D), C(0x13100D), C(0x324731), C(0x544229),
                         C(0x59342E), C(0x493B4C), C(0x2F4944), C(0x6E6862), C(0x5E5852)};
static const Pal* P = &LIGHT;
static uint8_t darkTheme = 0;

enum Lv : uint8_t { LG, LA, LR, LP, LT };
static uint16_t fillOf(Lv k) { const uint16_t v[] = {P->g, P->a, P->r, P->p, P->t}; return v[k]; }
static uint16_t onOf(Lv k) { const uint16_t v[] = {P->on_g, P->on_a, P->on_r, P->on_p, P->on_t}; return v[k]; }
static uint16_t tintOf(Lv k) { const uint16_t v[] = {P->tg, P->ta, P->tr, P->tp, P->tt}; return v[k]; }

enum Ft : uint8_t { F_TINY = 0, F_SR, F_S, F_M, F_L, F_XL };
static const GFXfont* const FONTS[] = {nullptr, &FreeSans9pt7b, &FreeSansBold9pt7b, &FreeSansBold12pt7b,
                                       &FreeSansBold18pt7b, &FreeSansBold24pt7b};
static const uint8_t CAP[] = {7, 13, 13, 17, 25, 34};

static Ft fontFor(int px, bool regular = false) {
  if (px <= 12) return F_TINY;
  if (px <= 17) return regular ? F_SR : F_S;
  if (px <= 24) return F_M;
  if (px <= 36) return F_L;
  return F_XL;
}

static void useFont(Ft f, uint8_t mag) {
  tft.setFont(FONTS[f]);
  tft.setTextSize(mag);
  tft.setTextWrap(false);
}

static int textW(const char* s, Ft f, uint8_t mag = 1) {
  useFont(f, mag);
  int16_t x1, y1;
  uint16_t w, h;
  tft.getTextBounds(s, 0, 50, &x1, &y1, &w, &h);
  return f == F_TINY ? (int)strlen(s) * 6 * mag : (int)w + x1;
}

static void txt(int x, int y, int w, const char* s, int px, uint16_t col, char align = 'l', bool regular = false, uint8_t mag = 1) {
  Ft f = fontFor(px, regular);
  int tw = textW(s, f, mag);
  while (tw > w && f > F_TINY) {
    f = (Ft)(f - 1);
    if (f == F_SR && !regular) f = F_TINY;
    tw = textW(s, f, mag);
  }
  int cx = align == 'c' ? x + (w - tw) / 2 : align == 'r' ? x + w - tw : x;
  useFont(f, mag);
  tft.setTextColor(col);
  if (f == F_TINY) tft.setCursor(cx, y + (px - 7 * mag) / 2);
  else tft.setCursor(cx, y + (px + CAP[f] * mag) / 2);
  tft.print(s);
}

static void txtFit(int x, int y, int w, const char* s, int px, uint16_t col, char align = 'l', bool regular = false) {
  char b[40];
  strlcpy(b, s, sizeof(b));
  Ft f = fontFor(px, regular);
  size_t n = strlen(b);
  if (textW(b, f) > w) {
    while (n > 2) {
      b[--n] = 0;
      char t[44];
      snprintf(t, sizeof(t), "%s..", b);
      if (textW(t, f) <= w) { strlcpy(b, t, sizeof(b)); break; }
    }
  }
  txt(x, y, w, b, px, col, align, regular);
}

static void wrapTxt(int x, int y, int w, const char* s, int px, uint16_t col, int lh) {
  char line[64] = "", word[40];
  const char* p = s;
  int ly = y;
  while (*p) {
    int n = 0;
    while (*p == ' ') p++;
    while (*p && *p != ' ' && n < 39) word[n++] = *p++;
    word[n] = 0;
    if (!n) break;
    char test[104];
    snprintf(test, sizeof(test), "%s%s%s", line, line[0] ? " " : "", word);
    if (textW(test, fontFor(px, true)) > w && line[0]) {
      txt(x, ly, w, line, px, col, 'c', true);
      ly += lh;
      strlcpy(line, word, sizeof(line));
    } else {
      strlcpy(line, test, sizeof(line));
    }
  }
  if (line[0]) txt(x, ly, w, line, px, col, 'c', true);
}

static void frame(int x, int y, int w, int h, uint16_t fill, int border, uint16_t bd) {
  tft.fillRect(x, y, w, h, bd);
  tft.fillRect(x + border, y + border, w - 2 * border, h - 2 * border, fill);
}

static void box(int x, int y, int w, int h, uint16_t fill, int border = 3, int shadow = 0) {
  if (shadow) tft.fillRect(x + shadow, y + shadow, w, h, P->bd);
  frame(x, y, w, h, fill, border, P->bd);
}

static void thick(int x0, int y0, int x1, int y1, int t, uint16_t c) {
  for (int dx = -(t / 2); dx <= t / 2; dx++)
    for (int dy = -(t / 2); dy <= t / 2; dy++) tft.drawLine(x0 + dx, y0 + dy, x1 + dx, y1 + dy, c);
}

static void arc(int cx, int cy, int r, int a0, int a1, int t, uint16_t c) {
  for (int a = a0; a <= a1; a += 4) {
    float rad = a * DEG_TO_RAD;
    tft.fillCircle(cx + cosf(rad) * r, cy + sinf(rad) * r, t / 2, c);
  }
}

enum Icon : uint8_t { I_BIN, I_LIDOPEN, I_SPEAKER, I_LOCK, I_TRI, I_CHECK, I_X, I_BT, I_TRUCK, I_SUN, I_MOON, I_RING, I_WIFI };

static void icon(Icon k, int x, int y, int s, uint16_t c, uint16_t bgc, int phase = 0) {
  float u = s / 24.0f;
  int t = max(2, (int)(s / 9));
  auto X = [&](float v) { return x + (int)(v * u); };
  auto Y = [&](float v) { return y + (int)(v * u); };
  switch (k) {
    case I_BIN:
      thick(X(5), Y(8), X(19), Y(8), t, c);
      thick(X(3), Y(8), X(21), Y(8), t, c);
      thick(X(5), Y(8), X(6.4), Y(20), t, c);
      thick(X(19), Y(8), X(17.6), Y(20), t, c);
      thick(X(6.4), Y(20), X(17.6), Y(20), t, c);
      thick(X(9), Y(4.5), X(15), Y(4.5), t, c);
      break;
    case I_LIDOPEN:
      thick(X(6), Y(11), X(18), Y(11), t, c);
      thick(X(6), Y(11), X(7.2), Y(20), t, c);
      thick(X(18), Y(11), X(16.8), Y(20), t, c);
      thick(X(7.2), Y(20), X(16.8), Y(20), t, c);
      thick(X(3.5), Y(8.5), X(16.5), Y(4.3), t, c);
      break;
    case I_SPEAKER:
      tft.fillTriangle(X(3), Y(12), X(12), Y(5.5), X(12), Y(18.5), c);
      tft.fillRect(X(3), Y(9.5), (int)(5 * u), (int)(5 * u) + 1, c);
      if (phase != 1) arc(X(13), Y(12), (int)(4 * u), -45, 45, t, c);
      if (phase != 2) arc(X(13), Y(12), (int)(8 * u), -50, 50, t, c);
      break;
    case I_LOCK:
      frame(X(4.5), Y(10.5), (int)(15 * u), (int)(10.5 * u), bgc, t, c);
      arc(X(12), Y(8.5), (int)(4 * u), 180, 360, t, c);
      thick(X(8), Y(8.5), X(8), Y(10.5), t, c);
      thick(X(16), Y(8.5), X(16), Y(10.5), t, c);
      thick(X(12), Y(14.5), X(12), Y(17.5), t, c);
      break;
    case I_TRI:
      thick(X(12), Y(3.2), X(22), Y(20.5), t, c);
      thick(X(22), Y(20.5), X(2), Y(20.5), t, c);
      thick(X(2), Y(20.5), X(12), Y(3.2), t, c);
      thick(X(12), Y(9.5), X(12), Y(14.5), t + 1, c);
      tft.fillCircle(X(12), Y(17.3), t, c);
      break;
    case I_CHECK:
      thick(X(4), Y(12.5), X(9), Y(17.5), t + 1, c);
      thick(X(9), Y(17.5), X(20), Y(6.5), t + 1, c);
      break;
    case I_X:
      thick(X(6), Y(6), X(18), Y(18), t + 1, c);
      thick(X(18), Y(6), X(6), Y(18), t + 1, c);
      break;
    case I_BT:
      thick(X(7), Y(7.5), X(17), Y(16.5), t, c);
      thick(X(17), Y(16.5), X(12), Y(21), t, c);
      thick(X(12), Y(21), X(12), Y(3), t, c);
      thick(X(12), Y(3), X(17), Y(7.5), t, c);
      thick(X(17), Y(7.5), X(7), Y(16.5), t, c);
      break;
    case I_TRUCK:
      frame(X(2), Y(7), (int)(11 * u), (int)(9 * u), bgc, max(2, t - 1), c);
      thick(X(13), Y(10), X(18), Y(10), t - 1, c);
      thick(X(18), Y(10), X(21.5), Y(13), t - 1, c);
      thick(X(21.5), Y(13), X(21.5), Y(16), t - 1, c);
      thick(X(13), Y(16), X(21.5), Y(16), t - 1, c);
      tft.fillCircle(X(6.5), Y(18.5), (int)(2.2 * u), c);
      tft.fillCircle(X(17), Y(18.5), (int)(2.2 * u), c);
      break;
    case I_SUN:
      tft.fillCircle(X(12), Y(12), (int)(4.5 * u), c);
      for (int a = 0; a < 360; a += 45) {
        float r = a * DEG_TO_RAD;
        thick(X(12 + cosf(r) * 7), Y(12 + sinf(r) * 7), X(12 + cosf(r) * 9.5f), Y(12 + sinf(r) * 9.5f), max(1, t - 1), c);
      }
      break;
    case I_MOON:
      tft.fillCircle(X(11), Y(12.5), (int)(8.5 * u), c);
      tft.fillCircle(X(15.5), Y(8.5), (int)(7 * u), bgc);
      break;
    case I_RING:
      for (int a = 0; a < 360; a += 40) arc(X(12), Y(12), (int)(9 * u), a + phase, a + phase + 22, t + 1, c);
      break;
    case I_WIFI:
      arc(X(12), Y(20), (int)(5 * u), 220, 320, t, c);
      arc(X(12), Y(20), (int)(10 * u), 220, 320, t, c);
      tft.fillCircle(X(12), Y(20), t, c);
      break;
  }
}

enum BtnId : uint8_t {
  B_NONE = 0, B_COLLECT, B_INFO, B_THEME, B_DONE, B_DONE_BACK, B_CAL_CANCEL, B_BACK, B_WIFI_SETUP, B_BT_CANCEL,
  B_BT_DONE, B_BT_RETRY, B_PIN_CANCEL, B_K0, B_K1, B_K2, B_K3, B_K4, B_K5, B_K6, B_K7, B_K8, B_K9, B_KDEL, B_KOK
};
struct Hit { int16_t x, y, w, h; uint8_t id; };
static Hit hits[24];
static uint8_t hitN = 0;
static void addHit(int x, int y, int w, int h, uint8_t id) {
  for (uint8_t i = 0; i < hitN; i++)
    if (hits[i].id == id && hits[i].x == x && hits[i].y == y) return;
  if (hitN < 24) hits[hitN++] = {(int16_t)x, (int16_t)y, (int16_t)w, (int16_t)h, id};
}

static void btn(int x, int y, int w, int h, const char* label, uint16_t fill, uint16_t on, int px, int ic, int isz, uint8_t id,
                bool pressed = false) {
  int o = pressed ? 4 : 0;
  if (pressed) tft.fillRect(x, y, w + 4, h + 4, P->bg == fill ? P->surf : P->bg);
  box(x + o, y + o, w, h, fill, 3, pressed ? 0 : 4);
  int lw = textW(label, fontFor(px));
  int gap = 5;
  while (ic >= 0 && lw + isz + gap > w - 12 && isz > 14) {
    isz -= 2;
    gap = 3;
  }
  if (ic >= 0 && lw + isz + gap > w - 12) ic = -1;
  int tw = lw + (ic >= 0 ? isz + gap : 0);
  int sx = x + o + (w - tw) / 2;
  if (ic >= 0) {
    icon((Icon)ic, sx, y + o + (h - isz) / 2, isz, on, fill);
    sx += isz + gap;
  }
  txt(sx, y + o + (h - px) / 2, x + o + w - 6 - sx, label, px, on);
  if (id) addHit(x, y, w + 4, h + 4, id);
}

static void chip(int x, int y, int w, int h, const char* label, uint16_t fill, uint16_t on, int px = 12, bool sh = false, int border = 2) {
  if (sh) tft.fillRect(x + 3, y + 3, w, h, P->bd);
  frame(x, y, w, h, fill, border, P->bd);
  txt(x + 2, y + (h - px) / 2, w - 4, label, px, on, 'c');
}

static uint32_t hashOf(const char* s) {
  uint32_t h = 2166136261u;
  while (*s) { h ^= (uint8_t)*s++; h *= 16777619u; }
  return h;
}

static bool fullDraw = true;
static uint32_t wcache[40];
static bool dirty(uint8_t id, const char* sig) {
  uint32_t h = hashOf(sig);
  if (fullDraw || wcache[id] != h) { wcache[id] = h; return true; }
  return false;
}

enum Screen : uint8_t { S_BOOT, S_CAL, S_MAIN, S_URGENT, S_PIN, S_COLLECT, S_DONE, S_TAMPER, S_LOCKED, S_INFO, S_BT, S_TOUCHCAL };
enum Overlay : uint8_t { O_NONE, O_INFO, O_PIN, O_BT };
static Screen cur = S_BOOT;
static Overlay overlay = O_NONE;
static uint32_t overlayMs = 0;
static bool pinForBt = false;
static char pinBuf[5] = "";
static uint32_t pinWrongUntil = 0;
static uint32_t doneShownAt = 0;
static uint32_t lastTouchMs = 0;
static uint8_t blink = 0, spin = 0;
static uint32_t lastAnim = 0;

static const char* lidText(bool open) { return open ? "LID: OPEN" : "LID: CLOSED"; }

static void topBar(const BinState& s, bool icons, int lidMode, const char* extraBack = nullptr) {
  char sig[48];
  snprintf(sig, sizeof(sig), "%d%d%d", icons, lidMode, darkTheme);
  if (dirty(0, sig)) {
    tft.fillRect(0, 0, 240, 34, P->surf);
    tft.fillRect(0, 34, 240, 3, P->bd);
    int nw = icons ? 60 : lidMode >= 0 ? 130 : 150;
    txt(8, 9, nw, BIN_LABEL, 16, P->tx);
    if (icons) {
      tft.fillRect(76, 6, 26, 26, P->bd);
      frame(74, 4, 26, 26, P->bg, 2, P->bd);
      txt(74, 10, 26, "i", 14, P->tx, 'c');
      addHit(0, 0, 72, 34, B_INFO);
      addHit(72, 0, 30, 34, B_INFO);
      tft.fillRect(106, 6, 26, 26, P->bd);
      frame(104, 4, 26, 26, P->bg, 2, P->bd);
      icon(darkTheme ? I_MOON : I_SUN, 107, 7, 20, P->tx, P->bg);
      addHit(102, 0, 34, 34, B_THEME);
    }
    if (extraBack) {
      frame(168, 4, 64, 26, P->bg, 2, P->bd);
      txt(168, 11, 64, extraBack, 12, P->tx, 'c');
      addHit(160, 0, 80, 34, B_BACK);
    }
  }
  if (lidMode >= 0) {
    snprintf(sig, sizeof(sig), "lid%d%d", lidMode, darkTheme);
    if (dirty(1, sig)) {
      bool open = lidMode == 1;
      chip(142, 6, 90, 22, lidText(open), open ? P->a : P->bg, open ? P->on_a : P->tx);
    }
  }
}

static void drawChips(const BinState& s, const Sim& sm, uint16_t tint) {
  char sig[24];
  snprintf(sig, sizeof(sig), "%d%d%d%d%d", s.wifi, s.v.motion, sm.mask != 0, s.calibrating, darkTheme);
  if (!dirty(2, sig)) return;
  tft.fillRect(0, 40, 240, 24, tint);
  int wx;
  if (s.wifi) { chip(8, 42, 52, 20, "WI-FI", P->g, P->on_g); wx = 66; }
  else { chip(8, 42, 70, 20, "OFFLINE", P->r, P->on_r); wx = 84; }
  addHit(4, 38, wx - 4, 28, B_INFO);
  if (s.v.motion) chip(wx, 42, 62, 20, "MOTION", P->cy, P->on_cy);
  else chip(wx, 42, 62, 20, "MOTION", P->surf, P->dim);
  int sx = wx + 68;
  if (sm.mask) chip(sx, 42, 40, 20, "SIM", P->p, P->on_p);
  else chip(sx, 42, 40, 20, "SIM", P->surf, P->dim);
  if (s.calibrating) chip(sx + 46, 42, 40, 20, "CAL", P->t, P->on_t);
  else chip(sx + 46, 42, 40, 20, "CAL", P->surf, P->dim);
}

static void fillBar(int x, int y, int w, int h, float pct, uint16_t col, float th) {
  box(x, y, w, h, P->surf, 3, 0);
  int inner = h - 6;
  int fh = (int)roundf(inner * constrain(pct, 0.0f, 100.0f) / 100.0f);
  if (fh > 0) {
    tft.fillRect(x + 3, y + 3 + inner - fh, w - 6, fh, col);
    tft.fillRect(x + 3, y + 3 + inner - fh, w - 6, min(2, fh), P->bd);
  }
  for (float t : {50.0f, th}) {
    int ty = y + 3 + (int)roundf(inner * (1 - t / 100.0f));
    tft.fillRect(x, ty - 1, w, 2, P->bd);
  }
}

static void reading(uint8_t id, int y, const char* label, const char* value, bool alarm, bool fault, int dot, uint16_t rowBg) {
  char sig[64];
  snprintf(sig, sizeof(sig), "%s|%d%d%d%d", value, alarm, fault, dot, darkTheme);
  if (!dirty(id, sig)) return;
  tft.fillRect(52, y - 2, 188, 22, rowBg);
  if (fault) {
    txt(56, y + 2, 90, label, 13, P->tx2, 'l', true);
    frame(178, y + 1, 12, 15, rowBg, 2, P->tx2);
    txt(178, y + 2, 12, "!", 12, P->tx2, 'c');
    txt(196, y + 1, 36, "--", 15, P->tx2, 'r');
    return;
  }
  if (alarm) {
    tft.fillRect(57, y + 2, 180, 18, P->bd);
    frame(54, y - 1, 180, 18, P->r, 2, P->bd);
    txt(60, y + 2, 90, label, 13, P->on_r, 'l', true);
    txt(120, y + 1, 106, value, 15, P->on_r, 'r');
    return;
  }
  txt(56, y + 2, 90, label, 13, P->tx2, 'l', true);
  int vw = textW(value, fontFor(15));
  if (dot >= 0) tft.fillRect(232 - vw - 15, y + 5, 9, 9, fillOf((Lv)dot));
  txt(110, y + 1, 122, value, 15, P->tx, 'r');
}

static void timerRow(uint8_t id, int y, int ic, const char* label, float frac, uint16_t col, uint16_t rowBg) {
  char sig[48];
  snprintf(sig, sizeof(sig), "%s|%d|%d", label, (int)(frac * 50), darkTheme);
  if (!dirty(id, sig)) return;
  frame(8, y, 224, 20, P->surf, 2, P->bd);
  icon((Icon)ic, 11, y + 2, 14, P->tx, P->surf);
  txt(29, y + 3, 200, label, 12, P->tx);
  tft.fillRect(10, y + 15, 220, 3, P->bg);
  tft.fillRect(10, y + 15, (int)(220 * constrain(frac, 0.0f, 1.0f)), 3, col);
}

static void clearRow(uint8_t id, int y, int h, uint16_t bgc) {
  if (dirty(id, "none")) tft.fillRect(0, y, 240, h, bgc);
}

static void bottomRow(const char* status, uint16_t fill, uint16_t on, int px) {
  char sig[40];
  snprintf(sig, sizeof(sig), "%s%d", status, darkTheme);
  if (!dirty(3, sig)) return;
  tft.fillRect(8 + 4, 274 + 4, 112, 36, P->bd);
  frame(8, 274, 112, 36, fill, 3, P->bd);
  txt(10, 274 + 11, 108, status, px, on, 'c');
  btn(128, 270, 104, 44, "COLLECT", P->surf, P->tx, 15, I_TRUCK, 20, B_COLLECT);
}

static void fmtTtf(const BinState& s, const Cal& c, char* out, size_t n) {
  out[0] = 0;
  if (s.fillRatePerHour <= 0 || s.v.fill >= c.fill_th) return;
  float h = (c.fill_th - s.v.fill) / s.fillRatePerHour;
  if (h > 72) return;
  if (h < 1) snprintf(out, n, "Full in ~%d min", max(1, (int)roundf(h * 60)));
  else snprintf(out, n, "Full in ~%.1f h", h);
}

static void lidRows(const BinState& s, const Cal& c, uint32_t now, int y, uint16_t rowBg, uint8_t id) {
  char label[40];
  if (s.lidOpen && s.v.motion && s.status == ST_OK) {
    timerRow(id, y, I_LIDOPEN, "LID HOLDING (motion)", 1, P->cy, rowBg);
  } else if (s.lidOpen && s.lidCloseAtMs) {
    float left = max<int32_t>(0, (int32_t)(s.lidCloseAtMs - now)) / 1000.0f;
    snprintf(label, sizeof(label), "LID CLOSES IN %.1f s", left);
    timerRow(id, y, I_LIDOPEN, label, left / max(1, (int)c.pir_hold_s), P->cy, rowBg);
  }
}

static bool lidRowActive(const BinState& s) { return s.lidOpen && (s.v.motion || s.lidCloseAtMs) && s.status == ST_OK; }
static bool buzzRowActive(const BinState& s, uint32_t now) { return s.buzzerOn || (int32_t)(s.buzzerUntilMs - now) > 0; }

static void buzzRow(const BinState& s, const Cal& c, uint32_t now, int y, uint16_t rowBg, uint8_t id) {
  char label[32];
  int32_t left = (int32_t)(s.buzzerUntilMs - now);
  if (left > 0) {
    snprintf(label, sizeof(label), "BUZZER %.1f s", left / 1000.0f);
    timerRow(id, y, I_SPEAKER, label, left / (max(1, (int)c.buzz_s) * 1000.0f), P->r, rowBg);
  } else {
    timerRow(id, y, I_SPEAKER, "BUZZER ON", 1, P->r, rowBg);
  }
}

static void drawMain(const BinState& s, const Cal& c, const Sim& sm, uint32_t now) {
  bool hcFault = !(s.okHC || (sm.mask & 1));
  int fill = (int)roundf(s.v.fill);
  Lv lvl = fill >= c.fill_th ? LR : fill >= 50 ? LA : LG;
  uint16_t tint = tintOf(lvl);
  char sig[64];
  snprintf(sig, sizeof(sig), "main%d%d", lvl, darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(tint);
  }
  topBar(s, true, s.lidOpen ? 1 : 0);
  drawChips(s, sm, tint);

  snprintf(sig, sizeof(sig), "%d|%d|%d|%.0f", fill, hcFault, darkTheme, c.fill_th);
  if (dirty(5, sig)) {
    tft.fillRect(8, 66, 224, 78, tint);
    fillBar(10, 68, 36, 150, hcFault ? 0 : fill, fillOf(lvl), c.fill_th);
    txt(56, 68, 100, "FILL LEVEL", 12, P->tx2);
    if (hcFault) {
      chip(150, 64, 82, 20, "NO READING", P->surf, P->tx2, 11);
      txt(56, 82, 176, "--", 56, P->tx);
    } else {
      chip(172, 64, 60, 20, fill >= c.fill_th ? "FULL" : fill >= 50 ? "FILLING" : "OK", fillOf(lvl), onOf(lvl));
      char n[8];
      snprintf(n, sizeof(n), "%d", fill);
      txt(56, 82, 176, n, 56, P->tx);
      int nw = textW(n, F_XL);
      txt(56 + nw + 2, 98, 40, "%", 26, P->tx);
    }
  }

  char val[24];
  bool hx = s.okHX || (sm.mask & 2), bme = s.okBME || (sm.mask & 8), bmeGas = s.okBME || (sm.mask & 4), gasOk = bmeGas && s.v.iaq_acc > 0;
  snprintf(val, sizeof(val), "%.2f kg", s.v.weight_g / 1000.0f);
  reading(6, 146, "Weight", val, hx && s.v.weight_g >= c.weight_cap, !hx, -1, tint);
  snprintf(val, sizeof(val), "%.1f C", s.v.temp);
  reading(7, 164, "Temperature", val, bme && s.v.temp >= c.temp_th, !bme, -1, tint);
  snprintf(val, sizeof(val), "%d%%", (int)roundf(s.v.humidity));
  reading(8, 182, "Humidity", val, bme && s.v.humidity >= c.hum_th, !bme, -1, tint);
  float gp = s.v.iaq / max(1.0f, c.gas_th) * 100;
  Lv al = gp >= 80 ? LR : gp >= 50 ? LA : LG;
  snprintf(val, sizeof(val), "%s %d", gp >= 80 ? "Poor" : gp >= 50 ? "Moderate" : "Good", (int)roundf(s.v.iaq));
  if (bmeGas && s.v.iaq_acc == 0) {
    if (s.v.gas_k > 0) snprintf(val, sizeof(val), "Warming %.0fk", s.v.gas_k);
    else strcpy(val, "Warming up");
    reading(9, 200, "Air", val, false, false, -1, tint);
  }
  else reading(9, 200, "Air", val, gasOk && gp >= 100, !gasOk, al, tint);

  bool lr = lidRowActive(s), br = buzzRowActive(s, now);
  if (lr && br) {
    lidRows(s, c, now, 222, tint, 10);
    buzzRow(s, c, now, 244, tint, 11);
  } else if (lr || br) {
    if (lr) lidRows(s, c, now, 224, tint, 10);
    else buzzRow(s, c, now, 224, tint, 10);
    clearRow(11, 244, 22, tint);
  } else {
    char t[32];
    fmtTtf(s, c, t, sizeof(t));
    snprintf(sig, sizeof(sig), "ttf%s%d", hcFault ? "" : t, darkTheme);
    if (dirty(10, sig)) {
      tft.fillRect(0, 220, 240, 26, tint);
      if (!hcFault && t[0]) txt(8, 230, 224, t, 13, P->tx);
    }
    clearRow(11, 246, 22, tint);
  }
  if (hcFault) bottomRow("BIN: ?", P->surf, P->tx2, 14);
  else bottomRow(fill >= c.fill_th ? "BIN: FULL" : "BIN: OK", fillOf(lvl), onOf(lvl), 14);
}

static void drawUrgent(const BinState& s, const Cal& c, const Sim& sm, uint32_t now) {
  char sig[48];
  snprintf(sig, sizeof(sig), "urg%d", darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->r);
  }
  topBar(s, true, s.lidOpen ? 1 : 0);
  if (fullDraw) {
    txt(12, 46, 170, "URGENT", 40, P->on_r);
    txt(12, 98, 200, "FILL LEVEL", 12, P->on_r);
    txt(12, 196, 216, "Bin is almost full.", 16, P->on_r, 'l', true);
    txt(12, 216, 216, "Collect it now!", 20, P->on_r);
  }
  snprintf(sig, sizeof(sig), "%d%d", (int)roundf(s.v.fill), sm.mask != 0);
  if (dirty(5, sig)) {
    tft.fillRect(10, 112, 176, 82, P->r);
    char n[8];
    snprintf(n, sizeof(n), "%d", (int)roundf(s.v.fill));
    txt(12, 112, 160, n, 80, P->on_r, 'l', false, 2);
    int nw = textW(n, F_XL, 2);
    txt(14 + nw, 146, 50, "%", 36, P->on_r);
    tft.fillRect(186, 94, 50, 26, P->r);
    if (sm.mask) chip(188, 96, 44, 20, "SIM", P->p, P->on_p);
  }
  snprintf(sig, sizeof(sig), "b%d", blink);
  if (dirty(12, sig)) {
    uint16_t bc = blink ? P->bd : P->r;
    for (int i = 0; i < 5; i++) tft.drawRect(i, 37 + i, 240 - 2 * i, 283 - 2 * i, bc);
    tft.fillRect(190, 46, 38, 38, P->r);
    if (blink) icon(I_SPEAKER, 190, 46, 38, P->on_r, P->r);
  }
  bool lr = lidRowActive(s), br = buzzRowActive(s, now);
  if (br) buzzRow(s, c, now, 244, P->r, 10);
  else if (lr) lidRows(s, c, now, 244, P->r, 10);
  else clearRow(10, 242, 24, P->r);
  bottomRow("BIN: FULL", P->surf, P->tx, 14);
}

static void drawPin(uint32_t now) {
  bool wrong = (int32_t)(pinWrongUntil - now) > 0;
  char sig[24];
  snprintf(sig, sizeof(sig), "pin%d%d", wrong, darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->bg);
    tft.fillRect(0, 0, 240, 34, P->surf);
    tft.fillRect(0, 34, 240, 3, P->bd);
    frame(6, 4, 84, 26, P->r, 2, P->bd);
    icon(I_X, 13, 10, 13, P->on_r, P->r);
    txt(30, 11, 56, "CANCEL", 12, P->on_r);
    addHit(0, 0, 96, 36, B_PIN_CANCEL);
    txt(98, 9, 134, "COLLECT PIN", 16, P->tx, 'r');
    const char* keys[] = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", "OK"};
    const uint8_t ids[] = {B_K1, B_K2, B_K3, B_K4, B_K5, B_K6, B_K7, B_K8, B_K9, B_KDEL, B_K0, B_KOK};
    for (int i = 0; i < 12; i++) {
      int r = i / 3, cc = i % 3;
      bool ok = i == 11;
      btn(8 + cc * 76, 98 + r * 46, 68, 42, keys[i], ok ? P->g : P->surf, ok ? P->on_g : P->tx, 22, -1, 0, ids[i]);
    }
    if (wrong) {
      frame(8, 288, 224, 26, P->r, 3, P->bd);
      txt(8, 294, 224, "WRONG PIN. Try again.", 14, P->on_r, 'c');
      for (int i = 0; i < 5; i++) tft.drawRect(i, 37 + i, 240 - 2 * i, 283 - 2 * i, P->r);
    } else {
      txt(8, 296, 224, "4 digits, then OK", 13, P->tx2, 'c', true);
    }
  }
  int n = wrong ? 4 : strlen(pinBuf);
  snprintf(sig, sizeof(sig), "%d", n);
  if (dirty(5, sig)) {
    for (int i = 0; i < 4; i++) {
      box(20 + i * 52, 46, 44, 40, P->surf, 3, 3);
      if (i < n) tft.fillCircle(20 + i * 52 + 22, 66, 6, P->tx);
    }
  }
}

static void drawCollect(uint32_t now) {
  char sig[24];
  snprintf(sig, sizeof(sig), "col%d", darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->g);
    BinState s = snapshot();
    topBar(s, false, 1);
    icon(I_LIDOPEN, 88, 84, 64, P->on_g, P->g);
    const char* rows[] = {"Lid: open", "Motion: ignored", "Tamper: paused", "Alerts: muted"};
    for (int i = 0; i < 4; i++) {
      tft.fillRect(28, 172 + i * 22, 9, 9, P->on_g);
      txt(44, 164 + i * 22, 180, rows[i], 16, P->on_g, 'l', true);
    }
    btn(8, 262, 224, 48, "DONE", P->surf, P->tx, 22, I_CHECK, 24, B_DONE);
  }
  char dots[24];
  int d = (now / 300) % 4;
  snprintf(dots, sizeof(dots), "COLLECTING%.*s", d, "...");
  if (dirty(5, dots)) {
    tft.fillRect(0, 44, 240, 34, P->g);
    txt(8, 48, 224, dots, 26, P->on_g, 'c');
  }
}

static void drawDone(uint32_t now) {
  char sig[24];
  snprintf(sig, sizeof(sig), "done%d", darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->tg);
    BinState s = snapshot();
    topBar(s, false, 0);
    txt(8, 50, 224, "COLLECTION", 22, P->tx, 'c');
    txt(8, 76, 224, "COMPLETE", 22, P->tx, 'c');
    box(84, 108, 72, 72, P->g, 3, 4);
    icon(I_CHECK, 94, 118, 52, P->on_g, P->g);
    btn(8, 198, 224, 56, "DONE", P->g, P->on_g, 26, -1, 0, B_DONE_BACK);
  }
  int left = 30 - (int)((now - doneShownAt) / 1000);
  if (left < 0) left = 0;
  snprintf(sig, sizeof(sig), "%d", left);
  if (dirty(5, sig)) {
    char t[48];
    snprintf(t, sizeof(t), "Back to the main screen in %d s", left);
    tft.fillRect(0, 264, 240, 20, P->tg);
    txt(8, 266, 224, t, 13, P->tx, 'c', true);
    frame(8, 286, 224, 16, P->surf, 2, P->bd);
    tft.fillRect(10, 288, 220 * left / 30, 12, P->g);
  }
}

static void drawTamper(const BinState& s, const Cal& c, uint32_t now) {
  bool latched = s.tamperPhase == 2;
  char sig[24];
  snprintf(sig, sizeof(sig), "tmp%d%d", latched, darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->r);
    txt(8, 82, 224, latched ? "TAMPER LATCHED" : "TAMPER DETECTED", 22, P->on_r, 'c');
    txt(8, 112, 224, "Bin moved or tilted", 15, P->on_r, 'c', true);
    if (latched) {
      box(16, 160, 208, 84, P->surf, 3, 4);
      txt(16, 176, 208, "RESET ON", 22, P->tx, 'c');
      txt(16, 204, 208, "DASHBOARD", 22, P->tx, 'c');
      txt(8, 252, 224, "Stays on until it is cleared", 13, P->on_r, 'c', true);
    } else {
      txt(8, 236, 224, "SECONDS, CLEARS IF STABLE", 12, P->on_r, 'c');
    }
    tft.fillRect(53, 279, 140, 32, P->bd);
    frame(50, 276, 140, 32, P->surf, 3, P->bd);
    icon(I_SPEAKER, 70, 281, 22, P->tx, P->surf);
    txt(98, 284, 90, latched ? "ALARM ON" : "ALARM", 15, P->tx);
  }
  char t[24];
  snprintf(t, sizeof(t), "Tilt: %.0f deg", s.v.tilt);
  if (dirty(5, t)) {
    tft.fillRect(8, 130, 224, 20, P->r);
    txt(8, 132, 224, t, 15, P->on_r, 'c', true);
  }
  if (!latched) {
    int left = (int)TAMPER_COUNTDOWN_S - (int)((now - s.tamperStartMs) / 1000);
    if (left < 0) left = 0;
    snprintf(t, sizeof(t), "%d", left);
    if (dirty(6, t)) {
      tft.fillRect(8, 150, 224, 84, P->r);
      txt(8, 152, 224, t, 84, P->on_r, 'c', false, 2);
    }
    float frac = 1.0f - (now - s.tamperStartMs) / (TAMPER_COUNTDOWN_S * 1000.0f);
    snprintf(t, sizeof(t), "%d", (int)(frac * 52));
    if (dirty(7, t)) {
      frame(16, 254, 208, 14, P->surf, 2, P->bd);
      tft.fillRect(18, 256, (int)(204 * constrain(frac, 0.0f, 1.0f)), 10, P->bd);
    }
  }
  snprintf(t, sizeof(t), "%d", blink);
  if (dirty(12, t)) {
    tft.fillRect(88, 16, 64, 58, P->r);
    icon(I_TRI, 88 + (blink ? 2 : -2), 14, 60, P->on_r, P->r);
    uint16_t bc = blink ? P->bd : P->r;
    for (int i = 0; i < 8; i++) tft.drawRect(i, i, 240 - 2 * i, 320 - 2 * i, bc);
  }
}

static void drawLocked(const BinState& s) {
  char sig[24];
  snprintf(sig, sizeof(sig), "lock%d", darkTheme);
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->p);
    icon(I_LOCK, 80, 46, 80, P->on_p, P->p);
    txt(8, 134, 224, "LOCKED", 40, P->on_p, 'c');
    txt(8, 184, 224, "Lid stays closed.", 15, P->on_p, 'c', true);
    txt(8, 204, 224, "Motion is ignored.", 15, P->on_p, 'c', true);
    frame(8, 228, 224, 34, P->surf, 3, P->bd);
    txt(8, 238, 224, "Unlock on the app or dashboard.", 13, P->tx, 'c');
  }
  topBar(s, true, 0);
  bottomRow("BIN: LOCKED", P->surf, P->tx, 13);
}

static void drawBoot(const BinState& s, uint32_t now) {
  if (dirty(4, "boot")) {
    fullDraw = true;
    tft.fillScreen(P->t);
    box(16, 18, 208, 66, P->surf, 3, 4);
    icon(I_BIN, 28, 30, 38, P->tx, P->surf);
    txt(76, 30, 140, "SMART", 20, P->tx);
    txt(76, 54, 140, "BIN", 20, P->tx);
    frame(16, 100, 208, 18, P->surf, 3, P->bd);
  }
  tft.fillRect(19, 103, min(202, (int)(now / 20)), 12, P->bd);
  const char* names[] = {"HC-SR04", "BME680", "HX711", "BMI160"};
  bool ok[] = {s.okHC, s.okBME, s.okHX, s.okIMU};
  for (int i = 0; i < 4; i++) {
    char sig[24];
    bool done = now > 1500u + i * 500u;
    snprintf(sig, sizeof(sig), "%d%d%d", i, done, ok[i]);
    if (!dirty(5 + i, sig)) continue;
    int y = 136 + i * 26;
    tft.fillRect(16, y, 210, 22, P->t);
    txt(20, y + 3, 120, names[i], 14, P->on_t);
    if (!done) chip(168, y, 52, 18, "...", P->surf, P->tx);
    else if (ok[i]) chip(168, y, 52, 18, "OK", P->surf, P->tx);
    else chip(168, y, 52, 18, "FAIL", P->r, P->on_r);
  }
  char ip[40];
  snprintf(ip, sizeof(ip), "IP: %s", s.wifi ? localIp().c_str() : "joining Wi-Fi...");
  if (dirty(9, ip)) {
    box(16, 252, 208, 56, P->surf, 3, 4);
    txt(26, 261, 190, ip, 16, P->tx);
    txt(26, 284, 190, "Firmware v" FW_VERSION, 13, P->tx2, 'l', true);
  }
}

static void drawCal(const BinState& s, const Cal& c, uint32_t now) {
  if (dirty(4, darkTheme ? "cal1" : "cal0")) {
    fullDraw = true;
    tft.fillScreen(P->t);
    frame(8, 222, 224, 44, P->surf, 3, P->bd);
    txt(8, 228, 224, "Keep the bin empty and", 13, P->tx, 'c');
    txt(8, 245, 224, "do not touch it.", 13, P->tx, 'c');
    btn(60, 268, 120, 42, "CANCEL", P->r, P->on_r, 15, -1, 0, B_CAL_CANCEL);
  }
  topBar(s, false, 0);
  char sig[40];
  snprintf(sig, sizeof(sig), "CALIBRATING%.*s", (int)((now / 300) % 4), "...");
  if (dirty(5, sig)) {
    tft.fillRect(0, 44, 240, 34, P->t);
    txt(8, 48, 224, "CALIBRATING", 26, P->on_t, 'c');
  }
  snprintf(sig, sizeof(sig), "%d", spin);
  if (dirty(6, sig)) {
    tft.fillRect(90, 80, 60, 60, P->t);
    icon(I_RING, 92, 82, 56, P->on_t, P->t, spin * 15);
  }
  char rows[3][24];
  strcpy(rows[0], "Weight zero");
  snprintf(rows[1], 24, "Empty: %.0f cm", c.d_empty);
  snprintf(rows[2], 24, "Full: %.0f cm", c.d_full);
  for (int i = 0; i < 3; i++) {
    bool ok = s.calSteps & (1 << i);
    char rs[40];
    snprintf(rs, sizeof(rs), "%s%d", rows[i], ok);
    if (!dirty(7 + i, rs)) continue;
    int y = 150 + i * 24;
    tft.fillRect(16, y, 210, 22, P->t);
    txt(20, y + 4, 140, rows[i], 15, P->on_t, 'l', true);
    chip(168, y, 52, 20, ok ? "OK" : "...", P->surf, P->tx);
  }
}

static String uptimeText() {
  uint32_t m = millis() / 60000;
  char b[24];
  if (m < 60) snprintf(b, sizeof(b), "%lu m", (unsigned long)m);
  else snprintf(b, sizeof(b), "%lu h %lu m", (unsigned long)(m / 60), (unsigned long)(m % 60));
  return b;
}

extern bool cloudFirebaseOn();

static void drawInfo(const BinState& s) {
  if (dirty(4, darkTheme ? "info1" : "info0")) {
    fullDraw = true;
    tft.fillScreen(P->bg);
    btn(8, 266, 164, 44, "WI-FI SETUP", P->bl, P->on_bl, 15, I_BT, 20, B_WIFI_SETUP);
    box(180, 266, 52, 44, P->surf, 3, 4);
    icon(darkTheme ? I_MOON : I_SUN, 194, 275, 24, P->tx, P->surf);
    addHit(176, 262, 64, 58, B_THEME);
  }
  topBar(s, false, -1, "BACK");
  String ip = s.wifi ? localIp() : String("Not connected");
  if (dirty(5, ip.c_str())) {
    box(8, 46, 224, 66, P->surf, 3, 4);
    txt(18, 54, 200, "IP ADDRESS", 12, P->tx2);
    txt(18, 72, 204, ip.c_str(), 27, P->tx);
  }
  char rows[6][2][28];
  strcpy(rows[0][0], "Wi-Fi");
  strlcpy(rows[0][1], s.wifi ? WiFi.SSID().c_str() : net.ssid.c_str(), 28);
  strcpy(rows[1][0], "Signal");
  if (s.wifi) snprintf(rows[1][1], 28, "%d dBm", s.rssi);
  else strcpy(rows[1][1], "--");
  strcpy(rows[2][0], "Cloud");
  strcpy(rows[2][1], !cloudFirebaseOn() ? "OFF" : s.cloudOk ? "Firebase OK" : "NOT SENDING");
  strcpy(rows[3][0], "Bluetooth");
  strcpy(rows[3][1], s.btOn ? "ON" : "OFF");
  strcpy(rows[4][0], "Firmware");
  strcpy(rows[4][1], "v" FW_VERSION);
  strcpy(rows[5][0], "Uptime");
  strlcpy(rows[5][1], uptimeText().c_str(), 28);
  for (int i = 0; i < 6; i++) {
    char sig[40];
    snprintf(sig, sizeof(sig), "%s", rows[i][1]);
    if (!dirty(6 + i, sig)) continue;
    int y = 124 + i * 23;
    tft.fillRect(8, y, 224, 20, P->bg);
    txt(10, y + 3, 90, rows[i][0], 13, P->tx2, 'l', true);
    txt(100, y + 2, 130, rows[i][1], 15, P->tx, 'r');
    tft.drawFastHLine(8, y + 20, 224, P->rule);
  }
}

static void btTop() {
  tft.fillRect(0, 0, 240, 34, P->bl);
  tft.fillRect(0, 34, 240, 3, P->bd);
  txt(8, 9, 170, "WI-FI SETUP", 16, P->on_bl);
  chip(190, 8, 42, 18, "BT", P->surf, P->tx);
}

static void mmss(char* b, size_t n, int32_t ms) {
  if (ms < 0) ms = 0;
  int s = ms / 1000;
  snprintf(b, n, "%d:%02d", s / 60, s % 60);
}

static void drawBt(uint32_t now) {
  BtView v = bleView();
  char sig[64];
  snprintf(sig, sizeof(sig), "bt%d%d%s%lu%d", v.phase, darkTheme, v.ip, (unsigned long)v.code, v.netCount);
  bool cancel = v.phase != BT_OK && v.phase != BT_FAIL && v.phase != BT_OFF;
  if (dirty(4, sig)) {
    fullDraw = true;
    tft.fillScreen(P->bg);
    btTop();
    if (cancel) btn(8, 266, 224, 44, "CANCEL", P->r, P->on_r, 15, -1, 0, B_BT_CANCEL);
    switch (v.phase) {
      case BT_IDLE:
      case BT_WAIT:
        txt(8, 120, 224, BIN_BLE_NAME, 22, P->tx, 'c');
        wrapTxt(16, 152, 208, "On the dashboard or app press PAIR A BIN and pick this name.", 13, P->tx2, 17);
        break;
      case BT_CODE: {
        txt(8, 46, 224, "PAIRING CODE", 14, P->tx2, 'c');
        box(8, 66, 224, 72, P->surf, 3, 4);
        char code[12];
        snprintf(code, sizeof(code), "%03lu %03lu", (unsigned long)(v.code / 1000), (unsigned long)(v.code % 1000));
        txt(8, 76, 224, code, 46, P->tx, 'c');
        wrapTxt(16, 146, 208, "Type it in the pairing window that pops up on the laptop or phone.", 13, P->tx2, 17);
        txt(8, 242, 224, "3 wrong codes stop pairing for 60 s.", 12, P->tx2, 'c');
        break;
      }
      case BT_LOCKOUT:
        box(8, 48, 224, 130, P->r, 3, 4);
        txt(16, 62, 208, "PAIRING STOPPED", 22, P->on_r, 'c');
        txt(16, 98, 208, "3 wrong codes", 20, P->on_r, 'c');
        wrapTxt(16, 192, 208, "Only someone who can see this screen can pair. The phone that tried was turned away.", 13, P->tx2, 17);
        break;
      case BT_PAIRED:
        box(96, 44, 48, 48, P->g, 3, 4);
        icon(I_CHECK, 102, 50, 36, P->on_g, P->g);
        txt(8, 98, 224, "PAIRED", 22, P->tx, 'c');
        wrapTxt(16, 126, 208, "Pick the network on the dashboard.", 13, P->tx2, 17);
        for (int i = 0; i < min<int>(v.netCount, 3); i++) {
          int y = 168 + i * 27;
          frame(8, y, 224, 23, P->surf, 2, P->bd);
          txtFit(14, y + 4, 156, v.nets[i], 13, P->tx);
          char r[12];
          snprintf(r, sizeof(r), "%d dBm", v.rssi[i]);
          txt(172, y + 4, 54, r, 12, P->tx2, 'r');
        }
        break;
      case BT_CONN:
        txt(8, 128, 224, "CONNECTING...", 22, P->tx, 'c');
        box(24, 166, 192, 34, P->surf, 3, 3);
        txtFit(32, 174, 176, v.ssid, 16, P->tx, 'c');
        txt(20, 214, 200, "Password received.", 13, P->tx2, 'c', true);
        break;
      case BT_OK:
        box(8, 48, 224, 140, P->g, 3, 4);
        txt(16, 58, 208, "CONNECTED", 22, P->on_g, 'c');
        txt(16, 88, 208, "IP ADDRESS", 12, P->on_g, 'c');
        txt(16, 102, 208, v.ip, 28, P->on_g, 'c');
        txtFit(16, 140, 208, v.ssid, 14, P->on_g, 'c', true);
        txt(16, 162, 208, "Type it in the dashboard.", 13, P->on_g, 'c', true);
        wrapTxt(16, 200, 208, "Bluetooth turns off by itself in 5 minutes.", 13, P->tx2, 17);
        btn(8, 266, 224, 44, "DONE", P->surf, P->tx, 18, I_CHECK, 20, B_BT_DONE);
        break;
      case BT_FAIL:
        box(8, 48, 224, 126, P->r, 3, 4);
        txt(16, 62, 208, "FAILED", 28, P->on_r, 'c');
        txt(16, 102, 208, v.why, 20, P->on_r, 'c');
        txt(16, 132, 208, "Check it and try again.", 14, P->on_r, 'c', true);
        wrapTxt(16, 190, 208, "Other reasons: network not found, or a 5 GHz network.", 13, P->tx2, 17);
        btn(8, 266, 108, 44, "RETRY", P->bl, P->on_bl, 16, -1, 0, B_BT_RETRY);
        btn(124, 266, 108, 44, "CANCEL", P->surf, P->tx, 16, -1, 0, B_BT_CANCEL);
        break;
      case BT_OFF:
        box(84, 60, 72, 72, P->surf, 3, 4);
        icon(I_CHECK, 94, 70, 52, P->tx, P->surf);
        txt(8, 150, 224, "BLUETOOTH OFF", 24, P->tx, 'c');
        txt(20, 184, 200, "Setup finished. Wi-Fi stays on.", 14, P->tx2, 'c', true);
        txt(8, 262, 224, "Back to the main screen", 12, P->tx2, 'c');
        break;
    }
  }
  char t[32], mm[12];
  switch (v.phase) {
    case BT_IDLE:
    case BT_WAIT:
      snprintf(sig, sizeof(sig), "%d%d", blink, (int)((v.offAtMs - now) / 1000));
      if (dirty(5, sig)) {
        tft.fillRect(84, 44, 76, 76, P->bg);
        int o = blink ? 0 : 2;
        box(88 + o, 48 + o, 64 - 2 * o, 64 - 2 * o, P->surf, 3, 4);
        icon(I_BT, 96 + o, 56 + o, 48 - 2 * o, P->bl, P->surf);
        mmss(mm, sizeof(mm), (int32_t)(v.offAtMs - now));
        snprintf(t, sizeof(t), "BT OFF IN %s", mm);
        tft.fillRect(20, 220, 204, 36, P->bg);
        box(24, 222, 192, 30, P->surf, 3, 3);
        txt(32, 229, 176, t, 14, P->tx, 'c');
      }
      break;
    case BT_CODE:
      mmss(mm, sizeof(mm), (int32_t)(v.phaseMs + BT_CODE_MS - now));
      if (dirty(5, mm)) {
        snprintf(t, sizeof(t), "NEW CODE IN %s", mm);
        tft.fillRect(20, 202, 204, 34, P->bg);
        box(24, 204, 192, 28, P->surf, 3, 3);
        txt(32, 210, 176, t, 14, P->tx, 'c');
      }
      break;
    case BT_LOCKOUT:
      mmss(mm, sizeof(mm), (int32_t)(v.phaseMs + BT_LOCKOUT_MS - now));
      if (dirty(5, mm)) {
        snprintf(t, sizeof(t), "Try again in %s", mm);
        tft.fillRect(12, 128, 216, 40, P->r);
        txt(8, 134, 224, t, 16, P->on_r, 'c', true);
      }
      break;
    case BT_CONN:
      snprintf(sig, sizeof(sig), "%d", spin);
      if (dirty(5, sig)) {
        tft.fillRect(86, 48, 68, 68, P->bg);
        icon(I_RING, 88, 50, 64, P->bl, P->bg, spin * 15);
      }
      break;
    case BT_OFF: {
      int32_t left = (int32_t)(v.phaseMs + 1500 - now);
      snprintf(sig, sizeof(sig), "%d", max<int32_t>(0, left / 100));
      if (dirty(5, sig)) {
        frame(60, 282, 120, 12, P->surf, 2, P->bd);
        tft.fillRect(62, 284, 116 * max<int32_t>(0, left) / 1500, 8, P->bd);
      }
      if (left <= 0) overlay = O_NONE;
      break;
    }
    default:
      break;
  }
}

static volatile bool tcReq = false, tcResetReq = false;
static bool tcActive = false, tcWaitUp = false;
static uint8_t tcStep = 0;
static uint32_t tcLastMs = 0, tcDoneMs = 0;
static long tcSx = 0, tcSy = 0;
static int tcN = 0;
static int tcRaw[4][2];
static const int TC_X[4] = {20, 219, 20, 219};
static const int TC_Y[4] = {20, 20, 299, 299};

void uiStartTouchCal() {
  tcReq = true;
  if (hUi) xTaskNotify(hUi, EV_STATE, eSetBits);
}

void uiResetTouchCal() {
  tcResetReq = true;
  if (hUi) xTaskNotify(hUi, EV_STATE, eSetBits);
}

static void drawTouchCal() {
  char sig[16];
  snprintf(sig, sizeof(sig), "tc%d%d", tcStep, darkTheme);
  if (!dirty(4, sig)) return;
  fullDraw = true;
  tft.fillScreen(P->bg);
  if (tcStep < 4) {
    txt(8, 112, 224, "TOUCH CALIBRATION", 16, P->tx, 'c');
    char b[32];
    snprintf(b, sizeof(b), "Press the cross  %d of 4", tcStep + 1);
    txt(8, 142, 224, b, 14, P->tx, 'c', true);
    wrapTxt(20, 170, 200, "Press firmly on the center, then let go. Wait 30 s to cancel.", 13, P->tx2, 17);
    int x = TC_X[tcStep], y = TC_Y[tcStep];
    tft.drawFastHLine(x - 14, y, 29, P->r);
    tft.drawFastVLine(x, y - 14, 29, P->r);
    tft.drawCircle(x, y, 7, P->r);
  } else {
    txt(8, 120, 224, "TOUCH SAVED", 22, P->tx, 'c');
    txt(8, 160, 224, "Buttons should line up now", 14, P->tx2, 'c', true);
  }
}

static Screen pickScreen(const BinState& s, const Cal& c, const Sim& sm, uint32_t now) {
  if (tcActive) return S_TOUCHCAL;
  if (s.booting) return S_BOOT;
  if (s.calibrating) return S_CAL;
  if (overlay == O_BT) return S_BT;
  if ((overlay == O_INFO || overlay == O_PIN) && now - overlayMs > SCREEN_TIMEOUT_MS) {
    overlay = O_NONE;
    buzz(T_BACK_MAIN);
  }
  if (overlay == O_INFO) return S_INFO;
  if (overlay == O_PIN) return S_PIN;
  if (doneShownAt && !s.collecting) {
    if (now - doneShownAt < 30000) return S_DONE;
    doneShownAt = 0;
    buzz(T_BACK_MAIN);
  }
  if (s.tamperPhase) return S_TAMPER;
  if (s.collecting) return S_COLLECT;
  if (s.status == ST_LOCKED) return S_LOCKED;
  if ((s.okHC || (sm.mask & 1)) && s.v.fill >= c.fill_th) return S_URGENT;
  return S_MAIN;
}

static void render(uint32_t now) {
  BinState s = snapshot();
  Cal c = calSnapshot();
  Sim sm = simSnapshot();
  if (s.doneAtMs && !doneShownAt && now - s.doneAtMs < 2000) doneShownAt = s.doneAtMs;
  Screen want = pickScreen(s, c, sm, now);
  if (want != cur) {
    cur = want;
    memset(wcache, 0, sizeof(wcache));
    hitN = 0;
  }
  fullDraw = false;
  switch (cur) {
    case S_BOOT: drawBoot(s, now); break;
    case S_CAL: drawCal(s, c, now); break;
    case S_MAIN: drawMain(s, c, sm, now); break;
    case S_URGENT: drawUrgent(s, c, sm, now); break;
    case S_PIN: drawPin(now); break;
    case S_COLLECT: drawCollect(now); break;
    case S_DONE: drawDone(now); break;
    case S_TAMPER: drawTamper(s, c, now); break;
    case S_LOCKED: drawLocked(s); break;
    case S_INFO: drawInfo(s); break;
    case S_BT: drawBt(now); break;
    case S_TOUCHCAL: drawTouchCal(); break;
  }
}

static void resetScreen() {
  memset(wcache, 0, sizeof(wcache));
  hitN = 0;
}

static void openOverlay(Overlay o) {
  overlay = o;
  overlayMs = millis();
  resetScreen();
}

static void onPress(uint8_t id) {
  BinState s = snapshot();
  if (id >= B_K0 && id <= B_K9) {
    if (strlen(pinBuf) < 4) {
      size_t n = strlen(pinBuf);
      pinBuf[n] = '0' + (id - B_K0);
      pinBuf[n + 1] = 0;
    }
    pinWrongUntil = 0;
    buzz(T_PIN_KEY);
    overlayMs = millis();
    return;
  }
  switch (id) {
    case B_COLLECT:
      pinForBt = false;
      pinBuf[0] = 0;
      buzz(T_OPEN);
      openOverlay(O_PIN);
      break;
    case B_INFO:
      buzz(T_OPEN);
      openOverlay(O_INFO);
      break;
    case B_THEME:
      darkTheme = !darkTheme;
      P = darkTheme ? &DARK : &LIGHT;
      saveTheme(darkTheme);
      buzz(darkTheme ? T_THEME_DARK : T_THEME_LIGHT);
      resetScreen();
      break;
    case B_BACK:
    case B_PIN_CANCEL:
      buzz(T_CANCEL);
      pinBuf[0] = 0;
      openOverlay(O_NONE);
      break;
    case B_KDEL: {
      size_t n = strlen(pinBuf);
      if (n) pinBuf[n - 1] = 0;
      buzz(T_PIN_DEL);
      overlayMs = millis();
      break;
    }
    case B_KOK: {
      char want[8];
      snprintf(want, sizeof(want), "%04u", calSnapshot().collect_pin);
      if (strlen(pinBuf) == 4 && !strcmp(pinBuf, want)) {
        pinBuf[0] = 0;
        if (pinForBt) {
          buzz(T_OPEN);
          openOverlay(O_BT);
          bleStart();
        } else {
          buzz(T_PIN_OK);
          openOverlay(O_NONE);
          sendCommand("COLLECT", SRC_SCREEN);
        }
      } else {
        pinBuf[0] = 0;
        pinWrongUntil = millis() + 1600;
        buzz(T_PIN_WRONG);
        overlayMs = millis();
      }
      break;
    }
    case B_DONE:
      sendCommand("COLLECT_DONE", SRC_SCREEN);
      doneShownAt = millis();
      break;
    case B_DONE_BACK:
      doneShownAt = 0;
      buzz(T_BACK_MAIN);
      resetScreen();
      break;
    case B_CAL_CANCEL:
      sendCommand("CALIBRATE_END", SRC_SCREEN);
      break;
    case B_WIFI_SETUP:
      pinForBt = true;
      pinBuf[0] = 0;
      buzz(T_OPEN);
      openOverlay(O_PIN);
      break;
    case B_BT_CANCEL:
      buzz(T_CANCEL);
      bleStop(false);
      openOverlay(O_NONE);
      break;
    case B_BT_DONE:
      buzz(T_KEY);
      openOverlay(O_NONE);
      break;
    case B_BT_RETRY:
      buzz(T_KEY);
      bleRetry();
      resetScreen();
      break;
  }
  (void)s;
}

static bool readTouch(int& x, int& y, int& rawX, int& rawY) {
  if (!touch.touched()) return false;
  TS_Point p = touch.getPoint();
  rawX = p.x;
  rawY = p.y;
  int rx = p.x, ry = p.y;
  if (touchCal.swap) { rx = p.y; ry = p.x; }
#if TOUCH_DEBUG
  Serial.printf("[touch] raw %d %d z %d\n", rx, ry, p.z);
#endif
  x = constrain(map(rx, touchCal.x0, touchCal.x1, 0, 239), 0, 239);
  y = constrain(map(ry, touchCal.y0, touchCal.y1, 0, 319), 0, 319);
  return p.z > 200;
}

static void tcBegin(uint32_t now) {
  tcActive = true;
  tcWaitUp = true;
  tcStep = 0;
  tcN = 0;
  tcSx = tcSy = 0;
  tcLastMs = now;
  overlay = O_NONE;
  resetScreen();
  buzz(T_CAL_START);
}

static void tcEnd() {
  tcActive = false;
  resetScreen();
}

static void tcFinish(uint32_t now) {
  float dxx = (tcRaw[1][0] + tcRaw[3][0]) / 2.0f - (tcRaw[0][0] + tcRaw[2][0]) / 2.0f;
  float dxy = (tcRaw[1][1] + tcRaw[3][1]) / 2.0f - (tcRaw[0][1] + tcRaw[2][1]) / 2.0f;
  bool sw = fabsf(dxy) > fabsf(dxx);
  float px[4], py[4];
  for (int i = 0; i < 4; i++) {
    px[i] = sw ? tcRaw[i][1] : tcRaw[i][0];
    py[i] = sw ? tcRaw[i][0] : tcRaw[i][1];
  }
  float left = (px[0] + px[2]) / 2, right = (px[1] + px[3]) / 2;
  float top = (py[0] + py[1]) / 2, bottom = (py[2] + py[3]) / 2;
  if (fabsf(right - left) < 800 || fabsf(bottom - top) < 800) {
    Serial.println("[touch] calibration rejected, presses too close together");
    buzz(T_CAL_CANCEL);
    raiseAlert("setup", "warning", "Touch calibration failed", "Presses were too close together. Try again.");
    tcEnd();
    return;
  }
  float kx = (right - left) / (TC_X[1] - TC_X[0]);
  float ky = (bottom - top) / (TC_Y[2] - TC_Y[0]);
  TouchCal t;
  t.swap = sw;
  t.x0 = lroundf(left - kx * TC_X[0]);
  t.x1 = lroundf(right + kx * (239 - TC_X[1]));
  t.y0 = lroundf(top - ky * TC_Y[0]);
  t.y1 = lroundf(bottom + ky * (319 - TC_Y[2]));
  touchCal = t;
  saveTouchCal();
  Serial.printf("[touch] saved swap %d x %d..%d y %d..%d\n", t.swap, t.x0, t.x1, t.y0, t.y1);
  buzz(T_SAVED);
  char meta[72];
  snprintf(meta, sizeof(meta), "%s touchscreen calibrated", BIN_LABEL);
  raiseAlert("setup", "success", "Touch calibrated", meta);
  publish(EV_CAL);
  tcStep = 4;
  tcDoneMs = now;
}

static void tcHandle(bool pressed, int rx, int ry, uint32_t now) {
  if (tcStep >= 4) {
    if (now - tcDoneMs > 2500) tcEnd();
    return;
  }
  if (tcWaitUp) {
    if (!pressed) tcWaitUp = false;
    tcLastMs = now;
    return;
  }
  if (pressed) {
    tcSx += rx;
    tcSy += ry;
    tcN++;
    tcLastMs = now;
    return;
  }
  if (tcN >= 3) {
    tcRaw[tcStep][0] = tcSx / tcN;
    tcRaw[tcStep][1] = tcSy / tcN;
    tcStep++;
    if (tcStep == 4) tcFinish(now);
    else buzz(T_CAL_STEP);
  }
  tcSx = tcSy = 0;
  tcN = 0;
  if (tcActive && tcStep < 4 && now - tcLastMs > 30000) {
    buzz(T_CAL_CANCEL);
    tcEnd();
  }
}

void uiInit() {
  SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI);
  pinMode(PIN_TOUCH_CS, OUTPUT);
  digitalWrite(PIN_TOUCH_CS, HIGH);
  tft.begin(40000000);
  tft.setRotation(TFT_ROTATION);
  touch.begin(SPI);
  touch.setRotation(TFT_ROTATION);
  darkTheme = loadTheme();
  P = darkTheme ? &DARK : &LIGHT;
  pinMode(PIN_TOUCH_IRQ, INPUT);
  tft.fillScreen(P->t);
}

void uiTask(void*) {
  bool down = false;
  uint32_t lastDraw = 0, holdSince = 0;
  for (;;) {
    uint32_t ev = 0;
    xTaskNotifyWait(0, UINT32_MAX, &ev, pdMS_TO_TICKS(down ? 30 : 100));
    uint32_t now = millis();
    if (now - lastAnim >= 500) {
      lastAnim = now;
      blink = !blink;
    }
    spin = (now / 120) % 24;

    if (tcReq) {
      tcReq = false;
      tcBegin(now);
    }
    if (tcResetReq) {
      tcResetReq = false;
      resetTouchCal();
      buzz(T_SAVED);
      publish(EV_CAL);
    }
    {
      int x, y, rx = 0, ry = 0;
      bool pressed = readTouch(x, y, rx, ry);
      if (!pressed) holdSince = 0;
      else if (!holdSince) holdSince = now;
      if (tcActive) {
        tcHandle(pressed, rx, ry, now);
        down = pressed;
        holdSince = 0;
      } else if (pressed && holdSince && now - holdSince > 5000) {
        holdSince = 0;
        tcBegin(now);
      } else if (pressed && !down && now - lastTouchMs > 220) {
        down = true;
        lastTouchMs = now;
        for (int i = hitN - 1; i >= 0; i--) {
          const Hit& h = hits[i];
          if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) {
            if (h.id < B_K0 && h.id != B_KOK && h.id != B_KDEL) buzz(T_KEY);
            onPress(h.id);
            break;
          }
        }
      } else if (!pressed) {
        down = false;
      }
    }
    if ((ev & ~EV_TOUCH) || now - lastDraw >= 100) {
      lastDraw = now;
      render(now);
    }
  }
}
