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
- The ESP32 accepts drive commands only after an explicit `arm` command; a `cmd` packet cannot arm the robot.
- While the joystick is held, the app repeats the current command every 150 ms to refresh the 700 ms watchdog.
- Firmware command watchdog disarms and zeros outputs if a valid drive command has not arrived for 700 ms.

## ESP32 → App

Handshake response:
```json
{"type":"hello","protocol":1,"firmware":"Robot007-SPP-0.4"}
```

Telemetry, normally every 200 ms:
```json
{"type":"telemetry","protocol":1,"firmware":"Robot007-SPP-0.4","armed":false,"throttle":0,"steer":0,"leftPwm":0,"rightPwm":0,"uptimeMs":12345,"batteryV":null,"distanceCm":null,"latitude":null,"longitude":null}
```

Unavailable optional sensors must be represented as JSON `null`, not fabricated values. The Android map displays the robot marker only when both `latitude` and `longitude` are real numeric GPS coordinates in valid ranges. The current generic firmware does not read a GPS module yet; wire/configure a GPS receiver before emitting these fields.

### Optional backward-compatible odometry / IMU extension

The Android app accepts these **additional** fields in a `telemetry` message. They are optional; the original protocol-v1 fields and existing firmware remain valid when they are absent.

```json
{
  "type": "telemetry",
  "protocol": 1,
  "armed": false,
  "pose": {
    "xCm": 125.0,
    "yCm": 48.0,
    "yawDeg": 32.5,
    "valid": true
  },
  "imuYawDeg": 32.2
}
```

- `pose.xCm`, `pose.yCm`: robot position in centimetres in a continuous local map frame.
- `pose.yawDeg`: heading in degrees; 0° points forward along +Y and positive angles turn clockwise.
- `pose.valid`: true only when the pose estimate is initialized and considered valid.
- `imuYawDeg`: optional IMU heading for diagnostics / future fusion.
- Flat aliases `xCm`, `yCm`, `yawDeg`, `odomValid` are also accepted by the Android parser.

The app only adds a LiDAR scan to the 2D occupancy map when a valid pose is present. It never invents a pose from GPS or from the LiDAR drawing. Existing firmware does not currently implement wheel encoders or IMU, so the map stays in “waiting for odometry” mode until a compatible sensor/odometry source emits these fields. This is an additive extension; do not change the existing `type`, `armed`, `throttle`, `steer`, `leftPwm`, or `rightPwm` meanings.

## Safety and setup

1. Pair the phone with Bluetooth device `Robot007` in Android settings.
2. Flash `Robot007_SPP.ino` to a **classic ESP32** board.
3. Motor outputs are disabled in firmware by default (`ENABLE_MOTOR_OUTPUTS = false`). Confirm driver type and wiring, change the pin constants, then enable outputs only after a bench test with wheels lifted.
4. Sensor pins are disabled by default. Configure them only after wiring the battery voltage divider / sensor and calibrating readings.
5. A software stop is not a safety-rated emergency stop. Use a physical power cut-off and test the watchdog before enabling motors.

The example motor pin mapping is generic and must be adapted to the actual motor driver. Do not enable the outputs based only on the example pins.
