# Smart-Bin

An ESP32-based IoT waste bin monitoring system with fill, weight, air-quality and tamper detection for priority-based collection.

Embedded Systems (Lecture) | TUP Manila | Group 2, BET-CpET 4A-M | IoT Level 6

## Links

- [Smart-Bin Online Document (Google Doc)](https://docs.google.com/document/d/1rWHQXjgQo8KrHvwuqEfFhKnTxFyY96tXlPZCjHuy8gE/edit?tab=t.0)
- [Gantt chart (PDF)](Documents/Figures/SmartBin_Gantt_Chart_Portrait.pdf)

## What it is

Two table-size smart bins, each with its own ESP32. Each bin measures how full it is, how heavy it is, and the temperature, humidity, pressure and air quality inside it. It detects tilting or tampering, opens its lid when a hand approaches, and reports to three interfaces:

- Web dashboard for the operator (admin)
- Android app for the collector
- On-bin 2.4 in touchscreen with PIN-protected collection

Collection priority ranks the fullest bin first, with an estimated time to full. Live data goes over a local REST API (about 1 s), and history and alerts go to ThingSpeak (every 15 s).

## Hardware per bin

| Part | Role |
|---|---|
| ESP32-WROOM-32D | Controller |
| HC-SR04 | Fill level (median of 5 readings) |
| HX711 + 5 kg load cell | Weight |
| BME680 | Temperature, humidity, pressure, air quality (IAQ) |
| BMI160 | Tilt / tamper |
| HC-SR501 PIR | Hand detection |
| SG90 servo | Lid |
| 2.4 in ILI9341 + XPT2046 | Touchscreen |
| RGB LED, buzzer module | Status and alarms |

## Repository layout

| Folder | Contents |
|---|---|
| `firmware/` | ESP32 firmware (`firmware/SmartBin`), Firebase rules and setup steps. See its README |
| `Web Dashboard/` | Operator web dashboard (`smartbin-dashboard.html`, single HTML file) and the integration guides. See its README |
| `Mockups/` | The UI mockups the dashboard, app and touchscreen follow |
| `Android App/` | Android app for operators and collectors (Kotlin, Compose). See its README |
| `hardware/` | Schematic, and `PCB-NOTES.md`: changes for the PCB from bench testing (pins, servo power, capacitors) |
| `Documents/` | Paper, figures, implementation plan, UI mockups compilation, references |

## Secrets

Copy `firmware/SmartBin/secrets.example.h` to `firmware/SmartBin/secrets.h` and fill in your own Wi-Fi, Firebase and ThingSpeak keys. `secrets.h` is git-ignored. Do not commit keys, passwords or the real Collect PIN.

## Team

Celeste, Brian Jay | Reyes II, Genesis Walter D. | Pepito, King Perth G. | Banogbanog, Rodel S. | Gunayan, Jade C.
