#pragma once
#include <Arduino.h>

void sensorsInit();
void sensorTask(void*);
void recomputeView();

void requestTare();
void requestCalWeight(float grams);
void requestGasReset();
void requestImuReference();
