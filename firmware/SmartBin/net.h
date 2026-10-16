#pragma once
#include <Arduino.h>

void netInit();
void wifiConnect();
void startWebServer();
void pushTask(void*);
