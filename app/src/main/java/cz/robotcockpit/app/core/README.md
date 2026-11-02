# Android Robot Core foundations

- DifferentialDrive: wheel-tick odometry; requires measured wheel geometry and encoder scale.
- OccupancyGrid: map storage and lidar ray integration using an already-known pose.
- RobotSafetySupervisor: app-side command limits and stale-telemetry gate.
- RobotCommandProtocol: builders for existing SPP JSON protocol v1.
- LocalVoiceIntentParser: deterministic Czech offline intent parsing.

Important: OccupancyGrid is not SLAM. It does not estimate pose, match scans, localize against a map or close loops. The autonomous mapping milestone is not complete.
