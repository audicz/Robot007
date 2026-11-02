from setuptools import find_packages, setup
from glob import glob
import os

package_name = "maros_robot_bringup"

setup(
    name=package_name,
    version="0.1.0",
    packages=find_packages(exclude=["test"]),
    data_files=[
        ("share/ament_index/resource_index/packages", ["resource/" + package_name]),
        ("share/" + package_name, ["package.xml"]),
        (os.path.join("share", package_name, "launch"), glob("launch/*.launch.py")),
        (os.path.join("share", package_name, "config"), glob("config/*.yaml")),
    ],
    install_requires=["setuptools"],
    zip_safe=True,
    maintainer="MAROS HOME ROBOT",
    maintainer_email="maintainer@example.invalid",
    description="ROS 2 bringup foundation for MAROS HOME ROBOT",
    license="MIT",
    tests_require=["pytest"],
    entry_points={"console_scripts": []},
)
