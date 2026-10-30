# Paper changes: match the written parts to the built bin

For: Brian. From: Gen. Updated 17 Oct 2026.

These are fixes to the parts of the paper that are already written, based on bench testing Bin 01 with the firmware on `main`. The empty placeholders (Chapter 3, Appendices C and D) are not covered here; the groupmates assigned to them are still writing them.

The list was checked against `Embedded Systems - Final Project - G2 v3.docx` in this folder. If the live Google Doc has changed since, check each item before editing.

Each item gives the section, what it says now, and what it should say.

## 1. Hardware changes (the text is now wrong)

### 2.2 Block Diagram
| Now | Change to | Why |
|---|---|---|
| "passive buzzer (PWM pin 12)" | "buzzer module (MH-FMD) on GPIO 4" | GPIO 12 is a boot pin. The buzzer held it high and the ESP32 would not boot. |
| TFT on the 5 V rail | TFT on the **3.3 V** rail | On 5 V the board kept resetting (brownout) and the screen showed garbage. |
| Servo on the same 5 V as the ESP32 | Servo on **its own 5 V branch** from the battery | Moving the servo pulled the supply down and reset the ESP32. |

### 2.4 Schematic Design (Figures 7 to 9)
Redraw the sheets from `hardware/PCB-NOTES.md`, then update the text:

- **Sheet 1:** remove IO12 from the used pins. If D1 (Schottky diode) is added, delete the note "the battery's 5V must not be used while code is being uploaded"; with the diode both can be connected at once.
- **Sheet 2:**
  - "BZ1 … is driven from IO12" → "driven from IO4".
  - "VCC and the backlight pin take 5V" → "take 3.3V".
  - Add: "T_IRQ has a 10 kΩ pull-up to 3.3V", "the display's SDO goes through jumper JP1, left open", and "M1 has its own 5V branch with a 470–1000 µF capacitor and a 2 A polyfuse".
- **Sheet 3:** "R1 (1kΩ) and R2 (2.2kΩ) divide it to about 3.4V" → "R1 (1kΩ) and R2 (2.0kΩ) divide it to about 3.3V".

### 2.5 Component Materials
- **Figure 11 (HC-SR04):** change the divider to 1kΩ / 2.0kΩ, the same as in 2.4.
- **Figure 19 (Buzzer):**
  - "for sound feedback from IO12" → "from IO4".
  - "warns the user when the **BMI160** detects poor air quality or when the air quality container is full" → "warns when the **BME680** detects poor air or when the bin is full".

## 2. Wording that does not match how the firmware works

### 2.3 Flowchart
| Now | Change to |
|---|---|
| "averages 5 HC-SR04 readings" | "takes the **median** of 5 HC-SR04 readings" (one bad echo does not move it) |
| "When the **LED** reaches 80%" | "When the **fill level** reaches the fill limit (80% by default, set by the operator)" |
| "if the LED lock function is activated" | "if auto-lock is on" |
| Tamper: "… bin is unlocked" | "A tamper starts a 10 s countdown. If the bin stays steady it clears by itself. If not, the alarm latches: the siren repeats every 2 s and the lid stays locked until an operator sends RESET TAMPER." |
| IAQ described as a measured value | "an **estimated** IAQ from the BME680's gas, humidity and temperature readings. It needs about 5 minutes to warm up (2 minutes once a baseline is saved), and the screen counts down until it is ready." |

### Figure 5 (Operator Flowchart)
- "these are saved to the bin's EEPROM" → "saved to the ESP32's flash memory (NVS)".
- Add a fifth task, **Touch calibration**: started from the dashboard or app, or by holding a finger on the screen for 5 s. The operator taps four crosses and the result is saved on the bin.

### Figure 6 (Collector Flowchart)
- "while reset a tamper alarm is **stayed with the collector**, as well as calibration and simulation" → "while resetting a tamper alarm, calibration and simulation **stay with the operator**".
- Bluetooth setup: "They type the 6-digit code that appears on the screen of the bin (it will expire after 60s and can be tried 3 times) followed by the name and password of the Wi-Fi" → "The 6-digit code on the bin's screen is typed into the phone's or computer's Bluetooth pairing window (it expires after 60 s). The app then lists the Wi-Fi networks the bin can see; the collector picks one and enters its password. The bin remembers the pairing for next time."

### 2.5 Component Materials
- **Figure 10 (ESP32):** "It reads all six inputs" → name them: HC-SR04, IR obstacle sensor, BME680, BMI160, HX711 and the touch panel. Optional: add "The IR sensor and touch panel use hardware interrupts, and the work is split into FreeRTOS tasks."
- **Figure 11 (HC-SR04):** "once the fill level reaches 80% or more" → "once it reaches the fill limit (80% by default)".
- **Figure 13 (BME680):** "since a hot temperature may cause it to **swell**" → "since a high temperature may mean a fire or hot material inside". Also say the IAQ is an estimate with a warm-up (same wording as 2.3).
- **Figure 15 (HX711):**
  - Start the paragraph with a capital: "A load cell is …".
  - "wet waste will be heavy, but will not be tall, and a bin can be heavy but not tall" → "a bin can be heavy but not full (wet waste) or full but light (bulky waste)".
- **Figure 16 (SG90):** "or when the collector taps DONE.." → remove the double period.
- **Figure 18 (KY-016):**
  - "It is powered from IO25, IO26 and IO33" → "It is driven from IO25, IO26 and IO33".
  - "and reverts to green when IO26 taps out the DONE signal" → "and turns green again after the collector taps DONE".

## 3. Claims to soften

- **Firebase and ThingSpeak:** the firmware has the code, but neither is set up or tested yet. The bin currently works over local Wi-Fi (the dashboard served by the bin). Either set them up and test before submission, or change "is the primary cloud" to "is designed to use Firebase … ", and put it in Recommendations if it is not done.

## 4. Appendix B (Bill of Materials)

- **The total is wrong.** The amounts add up to **₱2,750.00**, not ₱2,630.00.
- "RGB LED (common cathode, 5mm)" → "KY-016 RGB LED module (common cathode)", to match Figure 18.
- "Passive buzzer module (5V)" → "MH-FMD passive buzzer module". The schematic runs it on 3.3V.
- The 18650 battery shields are missing. Add them with prices, or say in the note that the group already owned them.
- If the PCB is made, add: SS34 Schottky diode, 2 A polyfuse, 470–1000 µF capacitor, 10 kΩ resistor, 100 nF capacitors, and the PCB itself.

## 5. Hand sensor: PIR replaced by an IR obstacle sensor (30 Oct 2026)

Everywhere the paper says **HC-SR501 PIR**, change it to **IR obstacle sensor module (FC-51 type)**: Block Diagram (2.2), Flowchart (2.3), Schematic (2.4, pin 27), Component Materials (2.5, its figure and paragraph) and Appendix B.

- Suggested paragraph: "An infrared obstacle sensor detects a hand in front of the lid. An IR LED sends out light and a photodiode sees it bounce back from a nearby object; a comparator turns that into a digital output that goes LOW when something is within range (set with its potentiometer, a few cm to about 30 cm). It runs on 3.3 V and is read with a hardware interrupt on GPIO 27."
- Why it was changed (good for the Results or Recommendations part): the PIR kept its output high for several seconds after any trigger, and the closing lid and the servo kept retriggering it, so the lid reopened by itself. The IR sensor only reacts to something in front of it, right now.
- Flowchart wording: "motion detected" → "hand detected". The lid stays open while the hand is there, closes a few seconds after it leaves (5 s by default), and stays shut for at least 2 s.
- BOM: replace the HC-SR501 line with the IR obstacle sensor module and its price.
