# ROS 2 workspace

Target: ROS 2 Jazzy on Ubuntu 24.04 (amd64 recommended for the Proxmox home-server VM).

Install the initial dependencies:

```bash
sudo apt update
sudo apt install -y ros-jazzy-desktop ros-jazzy-slam-toolbox ros-jazzy-navigation2 ros-jazzy-nav2-bringup ros-jazzy-robot-localization ros-jazzy-rosbridge-server python3-colcon-common-extensions
```

Build the bringup package from the repository root:

```bash
mkdir -p ~/maros_ws/src
cp -a ros2/maros_robot_bringup ~/maros_ws/src/
cd ~/maros_ws
source /opt/ros/jazzy/setup.bash
colcon build --symlink-install
source install/setup.bash
ros2 launch maros_robot_bringup server_bringup.launch.py
```

The launch file is a starting skeleton, not a complete robot bringup. SLAM will not produce a map until a real LiDAR publishes `/scan` and the robot publishes a valid TF chain including `odom -> base_link`. Do not start Nav2 or enable the motors until the sensor geometry and safety controller have been validated.

The rosbridge WebSocket endpoint defaults to port 9090. Keep it on the trusted LAN/VPN only; do not port-forward it to the Internet.
