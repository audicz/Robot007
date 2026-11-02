from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from ament_index_python.packages import get_package_share_directory
import os


def generate_launch_description():
    share = get_package_share_directory("maros_robot_bringup")
    slam_config = os.path.join(share, "config", "slam_toolbox.yaml")

    return LaunchDescription([
        DeclareLaunchArgument("start_slam", default_value="true"),
        DeclareLaunchArgument("start_rosbridge", default_value="true"),
        Node(
            package="slam_toolbox",
            executable="async_slam_toolbox_node",
            name="slam_toolbox",
            output="screen",
            parameters=[slam_config],
            condition=IfCondition(LaunchConfiguration("start_slam")),
        ),
        Node(
            package="rosbridge_server",
            executable="rosbridge_websocket",
            name="rosbridge_websocket",
            output="screen",
            parameters=[{"port": 9090, "address": "0.0.0.0"}],
            condition=IfCondition(LaunchConfiguration("start_rosbridge")),
        ),
    ])
