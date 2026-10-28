#pragma once
#include <Arduino.h>

void uiInit();
void uiTask(void*);
void isrTouch();
void uiStartTouchCal();
void uiResetTouchCal();
void uiShowBt();
