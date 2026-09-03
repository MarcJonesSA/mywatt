# MyWatt

An Android app for the Wattbike Atom. It connects over Bluetooth LE using the standard
Fitness Machine Service (FTMS), reads live power and cadence, and shows them on a
semicircular rev counter banded with training zones worked out from your FTP.

## What it does

- **Finds the bike.** Scans for BLE fitness machines and connects automatically to the
  trainer you rode last, or to the first device that looks like a Wattbike. If several
  candidates turn up, it asks which one.
- **Reads live data.** Subscribes to Indoor Bike Data notifications (characteristic
  `0x2AD2`) and decodes power, cadence, speed and elapsed time.
- **Shows it.** Every readout refreshes once a second; the needle sweeps smoothly between
  readings. If the trainer goes quiet for four seconds the readouts blank out.
- **Reconnects by itself.** A dropped connection retries three times, then falls back to
  rescanning until the bike comes back.

## The rev counter

A 180-degree dial from nine o'clock to three o'clock, full scale at twice FTP (rounded up
to the next 50 W) so threshold sits mid-dial with headroom for a sprint. The band ahead of
the needle is dimmed and the band behind it is lit. A white marker sits at FTP.

| Zone | Name | % of FTP | Colour | At FTP 220 W |
| --- | --- | --- | --- | --- |
| Z1 | Recovery | < 55% | Blue `#2E7BFF` | 0 - 120 W |
| Z2 | Endurance | 55 - 75% | Green `#22C55E` | 121 - 164 W |
| Z3 | Tempo | 75 - 90% | Yellow-green `#A3E635` | 165 - 197 W |
| Z4 | Threshold | 90 - 105% | Yellow `#FACC15` | 198 - 230 W |
| Z5 | VO2 Max | 105 - 120% | Orange `#F97316` | 231 - 263 W |
| Z6 | Anaerobic | > 120% | Red `#EF4444` | 264 W+ |

FTP is entered from the button in the title bar and stored in SharedPreferences
(`mywatt_settings`), defaulting to **220 W**. Changing it rescales every zone and the dial
immediately.

## Project layout

```
app/src/main/java/com/mywatt/app/
├── MainActivity.kt                 ride screen, permissions, 1 Hz refresh loop
├── bluetooth/
│   ├── BluetoothManager.kt         scanning, GATT, notifications, reconnect
│   └── FTMSParser.kt               Indoor Bike Data decoding
├── data/
│   ├── PowerZones.kt               zones and dial scale, all derived from FTP
│   └── RiderSettings.kt            FTP in SharedPreferences
└── ui/
    └── RevCounterView.kt           the gauge, drawn on canvas
```

## Building

Open the folder in Android Studio (Giraffe or newer) and run, or from the command line
with an Android SDK installed:

```bash
./gradlew assembleDebug     # build the APK
./gradlew test              # run the unit tests
```

- Kotlin 1.9.24, AGP 8.5.2, JDK 17
- `compileSdk` 34, `minSdk` 24, `targetSdk` 34

## Permissions

On Android 12 and above the app asks for `BLUETOOTH_SCAN` (declared `neverForLocation`)
and `BLUETOOTH_CONNECT`. On Android 11 and below it asks for `ACCESS_FINE_LOCATION`, which
is what BLE scanning required back then.

## Notes on the FTMS packet format

Indoor Bike Data is a 16-bit flags field followed by a variable run of fields, so it has to
be walked from the front rather than read at fixed offsets. Two details that are easy to
get wrong, and which the unit tests pin down:

- Flag bit 0 is **More Data**, and it is inverted: instantaneous speed is present when the
  bit is **clear**. Reading it the other way puts every later field two bytes out.
- Power is a **signed** 16-bit value; it goes negative when the flywheel drives the pedals.

Cadence arrives in half-rpm units and speed in hundredths of a km/h.
