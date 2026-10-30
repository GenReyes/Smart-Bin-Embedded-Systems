# PCB notes: changes from bench testing

For: Brian (PCB and schematic). From: Gen. Updated 17 Oct 2026.

These are the changes to make to `Smart-bin_Schematic_Diagram.pdf` before the PCB is drawn. Each one comes from something that went wrong on the breadboard with Bin 01. The firmware on `main` already uses the pins below.

## 1. Changes to the schematic

| # | Part | Now in the schematic | Change to | Why (what happened on the bench) |
|---|---|---|---|---|
| 1 | Buzzer BZ1 (MH-FMD) I/O | IO12 | **IO4** | GPIO12 is a boot pin. The MH-FMD's PNP transistor pulls its I/O pin up, so GPIO12 was high at power-on and the ESP32 picked the wrong flash voltage and would not boot (`boot:0x33`, `invalid header`). |
| 2 | TFT U2 VCC (pin 1) and LED (pin 8) | +5V | **+3.3V** | With the screen on 5 V the board kept browning out and the screen showed garbage. On 3.3 V it is stable. |
| 3 | M1 servo + (pin 2) | Same +5V as the ESP32 | **Its own 5 V branch** (section 2) | Moving the servo pulled the supply down and reset the ESP32 (`Brownout detector was triggered`). With the servo unplugged it never reset. |
| 4 | TFT T_IRQ (pin 14) to IO36 | Direct | Add **R 10 kΩ pull-up to +3.3V** | GPIO36 has no internal pull-up. Some screen modules have one, some do not. |
| 5 | TFT SDO/MISO (pin 9) to IO35 | Direct, shared with T_DO | Through **JP1, a 2-pin solder jumper, open by default** | The firmware never reads the display. On some modules its SDO does not let go of the shared line and blocks the touch chip. T_DO (pin 13) stays wired to IO35. |
| 6 | HC-SR04 divider R2 | 2.2 kΩ | **2.0 kΩ** (2.2 kΩ still works) | 1 kΩ / 2.2 kΩ gives 3.44 V on IO18; 1 kΩ / 2.0 kΩ gives 3.33 V, inside the ESP32's 3.3 V rating. |
| 7 | BMI160 INT1 (pin 10) | Not connected | Optional: to **IO34** through a 0 Ω resistor or jumper, not fitted | Lets a future firmware use the motion interrupt. Not needed now. |

Already right in the schematic (keep as they are): BMI160 CS to 3.3 V and SA0 to GND (address 0x68); BME680 SDO to GND (0x76); HX711 on 3.3 V; T_IRQ on IO36; the SD card pins left unconnected.

## 2. Power: give the servo its own branch

```
 Battery shield 5V ──┬── F1 polyfuse 2 A ──┬──► SERVO +5V (M1 pin 2)
                     │                     │     C1 470–1000 µF + C2 100 nF right at the servo connector
                     │                     │
                     │                     └── D1 Schottky (SS34) ──► ESP32 5V pin (U1 pin 19)
                     │                                                  HC-SR04 (+5V module)
 GND ────────────────┴─ wide trace, servo GND back to the battery connector on its own path
```

- **Servo branch:** the SG90 draws 100–250 mA moving and up to about 700 mA when stalled. Put a **470–1000 µF** electrolytic and a **100 nF** ceramic right at its connector. Run its + and GND straight to the battery connector, not through the ESP32 area.
- **D1 Schottky diode (SS34 or 1N5819)** between the battery 5 V and the ESP32's 5V pin. It stops the battery and the USB cable feeding each other, so the note "don't use battery 5V when uploading code" goes away: both can be connected at once. The ESP32 board then sees about 4.7 V, which is fine.
- **F1 polyfuse 2 A** at the battery input protects the board from a short.
- **3.3 V rail:** comes from the ESP32 board's own regulator (3V3 pin). Everything on it adds up to about 200 mA plus the ESP32's own Wi-Fi peaks (around 400 mA), which is near that regulator's limit. Keep the servo and HC-SR04 **off** the 3.3 V rail. The IR obstacle sensor (about 20 mA) goes **on** the 3.3 V rail, so its OUT pin never goes above 3.3 V.

## 3. Capacitors to add

| Where | Value | Why |
|---|---|---|
| ESP32 3V3 to GND, next to U1 | 10 µF + 100 nF | Wi-Fi bursts |
| ESP32 5V to GND, next to U1 | 10 µF | Supply ripple |
| Servo connector | 470–1000 µF + 100 nF | Section 2 |
| HX711 VCC to GND | 10 µF + 100 nF | The weight reading was jumpy |
| Each module connector (TFT, BME680, BMI160, HC-SR04, IR sensor) | 100 nF | Local decoupling |

## 4. Layout tips

- **HX711 and load cell:** put the HX711 near the load-cell connector, far from the servo and its wires. Keep E+, E-, A+, A- short and route them as twisted pairs. If the HX711 board has a RATE pin, tie it to GND (10 readings a second, quieter than 80).
- **SPI to the screen** (IO23 SCK, IO19 MOSI, IO35 MISO, IO17 CS, IO16 DC, IO15 T_CS): keep these traces short and together, with ground next to them. Long wires showed colored lines on the screen.
- **Strapping pins:** do not add pull-ups or pull-downs on IO12, IO0 or IO2. IO5 (TRIG) and IO15 (T_CS) are boot pins too, but as wired they are fine.
- **Connectors:** a 2-pin screw terminal or JST for the battery and one for the servo; pin headers for each module, labeled with the signal names so the boards cannot go in turned around.
- **Test points:** 5V, 3V3, GND, IO4 (buzzer), IO13 (servo), IO32/IO14 (HX711).

## 5. Final pin map (matches `firmware/SmartBin/config.h`)

| GPIO | Signal | Goes to |
|---|---|---|
| 4 | BUZZER | BZ1 I/O |
| 5 | US_TRIG | HC-SR04 TRIG |
| 13 | SERVO | M1 PWM |
| 14 | HX_SCK | HX711 SCK |
| 15 | TOUCH_CS | TFT T_CS |
| 16 | TFT_DC | TFT DC |
| 17 | TFT_CS | TFT CS |
| 18 | US_ECHO | HC-SR04 ECHO through the divider |
| 19 | SPI_MOSI | TFT SDI + T_DIN |
| 21 | I2C_SDA | BME680 SDA + BMI160 SDA |
| 22 | I2C_SCL | BME680 SCL + BMI160 SCL |
| 23 | SPI_SCK | TFT SCK + T_CLK |
| 25, 26, 33 | LED_R, LED_G, LED_B | RGB LED through its resistors |
| 27 | IR_OUT | IR obstacle sensor OUT (module on 3.3 V, LOW when something is in front) |
| 32 | HX_DT | HX711 DT |
| 34 | IMU_INT | BMI160 INT1, optional (input only) |
| 35 | SPI_MISO | T_DO, and TFT SDO through JP1 (input only) |
| 36 (SVP) | TOUCH_IRQ | T_IRQ, with 10 kΩ pull-up (input only) |

Do not use: 0, 2, 12 (boot pins), 1 and 3 (USB serial), 6 to 11 (flash). Free: 39 (SVN, input only).

## 6. Firmware settings that depend on the parts

In `firmware/SmartBin/config.h`:
- `BUZZER_ACTIVE_LOW`: the MH-FMD is a "low level trigger" module (PNP transistor). If the buzzer is on when it should be quiet, set this to `1`.
- `LED_ACTIVE_LOW`: `1` if the RGB LED is common-anode.
- Touch calibration is done on the finished bin (hold a finger on the screen for 5 s), so screen orientation on the PCB does not matter.
