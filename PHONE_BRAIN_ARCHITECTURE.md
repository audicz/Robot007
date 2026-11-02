# MAROS HOME ROBOT — PHONE BRAIN

## Selected configuration

- Dedicated Android phone mounted on the robot.
- Bluetooth Classic RFCOMM SPP to the ESP32, preserving Robot007 JSON-lines protocol v1.
- Local-first operation: no home server and no cloud dependency for basic driving, mapping, navigation or voice commands.
- Differential-drive chassis.
- ESP32 remains the deterministic motor controller and local safety device. Android is the high-level brain, not a safety-rated controller.

## Android modules

| Module | Responsibility |
|---|---|
| RobotCoreService | Connected-device foreground service and SPP lifecycle |
| SppTransport | RFCOMM socket, line framing, reconnect and protocol |
| RobotStateStore | Observable connection, telemetry, safety and mission state |
| SensorHub | Lidar, radar, IMU and encoder data normalization |
| OdometryEngine | Differential-drive wheel odometry |
| MappingEngine | Occupancy grid and scan integration |
| SlamEngine | Planned scan matching, localization and loop closure |
| NavigationEngine | Planned global/local planning and obstacle avoidance |
| VoiceEngine | Offline speech recognition and local intent parsing |
| LocalAssistant | Local commands, status and task orchestration |
| Cockpit UI | Full-screen map, sensor overlays, joystick, STOP and diagnostics |

## Bluetooth transport

Use Bluetooth Classic RFCOMM SPP with UUID 00001101-0000-1000-8000-00805F9B34FB. The current protocol is newline-delimited JSON: hello, arm, cmd, stop and telemetry. Android uses a connected-device foreground service while keeping the link alive. Android 12+ requires runtime Bluetooth permissions; Android 14+ requires FOREGROUND_SERVICE_CONNECTED_DEVICE and the matching service type.

The ESP32 command watchdog remains mandatory. A phone-side STOP is not a substitute for a physical power cutoff.

## Local AI

1. First milestone: deterministic offline Czech intent parser. STOP never depends on an LLM.
2. Speech-to-text: sherpa-onnx Android runtime with a Czech-capable offline model selected after testing on the actual phone.
3. Text-to-speech: installed local Android TTS voice first; optional local neural TTS model later.
4. Optional on-device LLM: only after exact phone SoC, RAM and thermal limits are known. It may translate natural language into a constrained intent schema; it must never directly emit motor PWM or bypass safety validation.

## Mapping and navigation

The repository includes differential-drive odometry and occupancy-grid ray integration. These are foundations, not a complete SLAM system. Full SLAM additionally requires scan matching, localization, map-to-odom correction and loop closure. The exact vacuum LiDAR protocol and mounting transform must be identified first. HLK-LD2451 target tracks complement but do not replace a 2D laser scan.

Planned sequence:
1. Verify classic ESP32, motor driver, encoder wiring, wheel radius and wheel track.
2. Integrate a persistent Bluetooth service and verify protocol compatibility.
3. Define sensor packet contracts and implement the exact LiDAR parser.
4. Calibrate wheel odometry.
5. Integrate 2D scan matching and pose-graph SLAM using Android NDK/JNI or a validated native library.
6. Add localization, occupancy mapping and persistence.
7. Add safe global planning and local obstacle avoidance.
8. Integrate offline speech recognition and local command execution.
9. Replace cockpit placeholder with the live full-screen map and sensor overlays.

## Phone requirements

A dedicated phone should have a modern 64-bit ARM SoC, 8 GB RAM preferred, active cooling or good ventilation, stable power sized for peak draw, and charge limiting if supported. Keep the robot core in a foreground service, not tied to an open screen. Disable aggressive battery optimization for the app where the OS permits.

## Hardware facts required before autonomous motion

- Exact phone model, Android version, SoC and RAM.
- Exact ESP32 module. Classic SPP requires original ESP32; ESP32-S3 does not support Bluetooth Classic SPP.
- LiDAR make/model, connector pinout, supply voltage and UART/USB protocol.
- Motor driver and wiring.
- Encoder pulses/revolution after gearbox, wheel radius and wheel track.
- IMU model and mounting orientation.

Never enable motor outputs until wheel polarity, encoder direction, command watchdog, physical cutoff and STOP behavior have been bench-tested with wheels lifted.
