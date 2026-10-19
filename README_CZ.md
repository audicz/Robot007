# Robot Cockpit — ESP32 Bluetooth SPP

Android ground-control app (Kotlin + Jetpack Compose) for a robot controlled by a classic ESP32 over Bluetooth SPP. No ROS bridge or Wi-Fi is used for the control link.

## Repository contents
- `app/` — Android GUI, paired-device picker, joystick, stop/disarm, telemetry.
- `firmware/Robot007_SPP/Robot007_SPP.ino` — matching ESP32 Arduino firmware.
- `PROTOCOL_SPP.md` — line-delimited JSON protocol v1.

## First setup
1. In Arduino IDE, select an **original ESP32** board with Bluetooth Classic support, install the Espressif ESP32 board package and flash the firmware.
2. Pair the phone with `Robot007` from Android Bluetooth settings.
3. Install the debug APK from the latest successful GitHub Actions run, open **Bluetooth**, select the paired device and connect.
4. The first firmware build has motor outputs disabled by default. Check the exact board, motor driver, wiring, direction and power stage before enabling them in firmware.
5. With the drive wheels lifted and a physical power cut-off available, test connection, STOP and the 700 ms command watchdog before any ground test.

## Safety / hardware adaptation
- The included GPIO assignment is only an example for a dual H-bridge with PWM + two direction pins per motor. It is not tailored to an unknown motor driver.
- Battery and ultrasonic sensor inputs are disabled by default. Configure and calibrate them for your hardware.
- ESP32 ADC inputs are not battery-tolerant; use a properly designed divider. Ultrasonic ECHO signals must be level-shifted to 3.3 V if the sensor outputs 5 V.
- A phone STOP is not a safety-rated emergency stop. Add a physical power cutoff.
- Bluetooth Classic SPP is supported by original ESP32, but not ESP32-S2, ESP32-S3 or ESP32-C3. Those chips need BLE or another transport.
