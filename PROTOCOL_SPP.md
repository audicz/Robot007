# Robot007 — Bluetooth SPP protocol v1

Transport: Bluetooth Classic SPP, UTF-8, one JSON object per line, terminated by LF (\n). The Android app connects to the paired ESP32 using the standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.

## App → ESP32

Initial handshake:
```json
{"type":"hello","protocol":1}
```

Arm (zero command; does not move the robot):
```json
{"type":"arm","armed":true}
```

Drive command, values normalized to -1.0…1.0:
```json
{"type":"cmd","throttle":0.25,"steer":-0.1,"armed":true}
```

Stop/disarm:
```json
{"type":"stop","armed":false}
```

- Positive throttle means forward; negative means reverse.
- Positive steer means turn right. Firmware mixes left = throttle + steer and right = throttle - steer. Reverse motor polarity in the driver configuration if your robot's wiring differs.
- The app sends neutral on joystick release and stop/disarm when STOP or disconnect is requested.
- Firmware command watchdog disarms and zeros outputs if a valid drive command has not arrived for 700 ms.

## ESP32 → App

Handshake response:
```json
{"type":"hello","protocol":1,"firmware":"Robot007-SPP-0.3"}
```

Telemetry, normally every 200 ms:
```json
{"type":"telemetry","protocol":1,"firmware":"Robot007-SPP-0.3","armed":false,"throttle":0,"steer":0,"leftPwm":0,"rightPwm":0,"uptimeMs":12345,"batteryV":null,"distanceCm":null}
```

Unavailable optional sensors must be represented as JSON `null`, not fabricated values.

## Safety and setup

1. Pair the phone with Bluetooth device `Robot007` in Android settings.
2. Flash `Robot007_SPP.ino` to a **classic ESP32** board.
3. Motor outputs are disabled in firmware by default (`ENABLE_MOTOR_OUTPUTS = false`). Confirm driver type and wiring, change the pin constants, then enable outputs only after a bench test with wheels lifted.
4. Sensor pins are disabled by default. Configure them only after wiring the battery voltage divider / sensor and calibrating readings.
5. A software stop is not a safety-rated emergency stop. Use a physical power cut-off and test the watchdog before enabling motors.

The example motor pin mapping is generic and must be adapted to the actual motor driver. Do not enable the outputs based only on the example pins.
