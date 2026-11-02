# MAROS HOME ROBOT

Project direction: Android phone as the user-facing intelligence and mission-control brain; the home server runs ROS 2 mapping/navigation; an ESP32 performs deterministic motor control and local safety on a differential-drive base.

## System topology

- Android app (Kotlin/Compose): operator UI, voice/AI interaction, mission/task planner, map display, teleoperation, robot status.
- Home server (Ubuntu 24.04 VM on Proxmox recommended): ROS 2 Jazzy, SLAM Toolbox, Nav2, robot_state_publisher, rosbridge WebSocket, persistent maps/logs.
- Robot controller (classic ESP32 or ESP32-S3 depending on transport): motor PWM/direction, encoder sampling, closed-loop wheel speed, watchdog, hard-stop input, sensor acquisition.
- Sensors: 2D vacuum LiDAR, wheel encoders, IMU, HLK-LD2451/LD2450. LiDAR model and electrical interface must be identified before implementing its driver.

## Network and data flow

1. ESP32 connects to the robot Wi-Fi SSID and exchanges framed telemetry/commands with a ROS 2 TCP gateway on the home server.
2. The gateway publishes sensor and odometry data into ROS 2 and forwards velocity setpoints to the ESP32.
3. SLAM Toolbox consumes `/scan`, `/odom`, and TF to build the occupancy map.
4. Nav2 consumes the map, costmaps, TF and odometry; it produces velocity commands.
5. rosbridge exposes selected ROS topics/actions over WebSocket to the Android app.
6. Android renders the map and sends goals/teleoperation commands. The server may continue an already-authorized navigation task if the phone disconnects; the ESP32 still enforces its own command timeout and stop conditions.

Do not expose ROS DDS, rosbridge, or the ESP32 control port directly to the public Internet. Use the trusted home LAN or a private VPN such as ZeroTier, authentication, and firewall allow-lists.

## ROS graph contract

- `/scan` — sensor_msgs/LaserScan from the actual LiDAR driver.
- `/imu/data` — sensor_msgs/Imu.
- `/wheel/odom` — nav_msgs/Odometry from wheel encoders.
- `/odom` — fused odometry from robot_localization (when configured).
- `/tf`, `/tf_static` — map/odom/base_link/laser/imu transforms.
- `/cmd_vel` — geometry_msgs/Twist; server gateway converts linear.x and angular.z to wheel setpoints.
- `/robot/status` — controller state, battery, link quality, safety state.
- `/ld2451/tracks` — radar targets; never treat radar tracks as a substitute for the LiDAR scan.

## Differential-drive kinematics

For wheel separation B and wheel radius R:
- left wheel linear speed = v - (w * B / 2)
- right wheel linear speed = v + (w * B / 2)
- wheel angular speed = wheel linear speed / R

The physical values R, B, encoder counts/revolution, gear ratio, motor driver type, polarity and maximum safe speed must be measured/configured. Do not enable motor outputs based on generic pin examples.

## Safety requirements

- Hardware power cutoff accessible on the chassis.
- ESP32 watchdog: loss of valid motion command causes immediate zero motor output and disarm.
- Startup is disarmed; explicit arm action required.
- STOP is latched until an explicit re-arm.
- Reject NaN/out-of-range commands; clamp velocity and acceleration.
- On Wi-Fi/server loss: stop locally; never coast indefinitely on the last command.
- Test with wheels lifted, current-limited supply and physical cutoff before floor tests.
- Phone STOP is a convenience control, not a safety-rated emergency stop.

## Software baseline

- ROS 2 Jazzy on Ubuntu 24.04 LTS (supported through May 2029).
- SLAM Toolbox for 2D mapping.
- Nav2 for localization/navigation and obstacle avoidance.
- robot_localization for IMU + wheel-odometry fusion.
- rosbridge_suite WebSocket for the Android client.
- Android: Kotlin, Jetpack Compose, coroutines, WebSocket client, OpenGL/Canvas-based 2D map renderer.
- ESP32: ESP-IDF preferred for production firmware. Existing Robot007 SPP firmware remains a bench/service fallback until the new Wi-Fi gateway protocol is implemented and tested.

## Repository implementation phases

1. Foundation (this branch): architecture contract and ROS 2 bringup package skeleton.
2. Hardware inventory: exact LiDAR model/protocol, ESP32 variant, motor driver, encoder electrical interface, IMU, wheel geometry.
3. ESP32 controller: wheel speed PID, encoder odometry, Wi-Fi transport, watchdog and hard-stop.
4. ROS gateway: TCP framing, telemetry parsing, odometry/IMU publishing, command forwarding.
5. SLAM: LiDAR driver, TF tree, odometry calibration and mapping.
6. Nav2: footprint, inflation, velocity limits, recovery behaviors and map persistence.
7. Android: server connection, live map, manual drive, STOP, navigation goals, telemetry, AI/voice.
8. Home Assistant integration and autonomous missions.

## Hardware facts still required

The current repository's Robot007 firmware deliberately disables motor outputs and uses example GPIOs only. Before enabling any motor output, record:
- ESP32 exact board/module and whether it is classic ESP32 or S3.
- Motor driver model and control interface (PWM+DIR, dual H-bridge, serial, etc.).
- Encoder type, voltage, pulses per motor revolution, gear ratio and pinout.
- Wheel diameter and distance between wheel centers.
- LiDAR manufacturer/model, supply voltage, UART/USB interface, baud rate and packet protocol.
- IMU model and mounting orientation.
- Home server OS/CPU and whether ROS runs in a Proxmox Ubuntu VM.
