#pragma once

#include "esphome/core/component.h"
#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/text_sensor/text_sensor.h"

#include <Arduino.h>
#include <BluetoothSerial.h>

namespace esphome {
namespace robot007_spp {

class Robot007SPP : public Component {
 public:
  void set_device_name(const std::string &name) { device_name_ = name; }
  void set_enable_motor_outputs(bool enabled) { enable_motor_outputs_ = enabled; }
  void set_left_pins(int pwm, int in1, int in2) { left_pwm_pin_ = pwm; left_in1_pin_ = in1; left_in2_pin_ = in2; }
  void set_right_pins(int pwm, int in1, int in2) { right_pwm_pin_ = pwm; right_in1_pin_ = in1; right_in2_pin_ = in2; }
  void set_sensor_pins(int battery, int trig, int echo, float divider) {
    battery_adc_pin_ = battery; ultrasonic_trig_pin_ = trig; ultrasonic_echo_pin_ = echo; battery_divider_ratio_ = divider;
  }
  void set_armed_sensor(binary_sensor::BinarySensor *sensor) { armed_sensor_ = sensor; }
  void set_left_pwm_sensor(sensor::Sensor *sensor) { left_pwm_sensor_ = sensor; }
  void set_right_pwm_sensor(sensor::Sensor *sensor) { right_pwm_sensor_ = sensor; }
  void set_battery_sensor(sensor::Sensor *sensor) { battery_sensor_ = sensor; }
  void set_distance_sensor(sensor::Sensor *sensor) { distance_sensor_ = sensor; }
  void set_status_sensor(text_sensor::TextSensor *sensor) { status_sensor_ = sensor; }

  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::LATE; }

 protected:
  void handle_line_(const String &line);
  void stop_robot_(const char *reason);
  void apply_motor_output_();
  void drive_one_motor_(int pwm_pin, int in1_pin, int in2_pin, int pwm);
  float read_number_(const String &line, const char *key, float fallback);
  bool read_bool_(const String &line, const char *key, bool fallback);
  String read_type_(const String &line);
  float read_battery_voltage_();
  float read_distance_cm_();
  void publish_telemetry_();

  BluetoothSerial serial_bt_;
  std::string device_name_{"Robot007"};
  bool enable_motor_outputs_{false};
  int left_pwm_pin_{25}, left_in1_pin_{26}, left_in2_pin_{27};
  int right_pwm_pin_{14}, right_in1_pin_{32}, right_in2_pin_{33};
  int battery_adc_pin_{-1}, ultrasonic_trig_pin_{-1}, ultrasonic_echo_pin_{-1};
  float battery_divider_ratio_{2.0f};

  binary_sensor::BinarySensor *armed_sensor_{nullptr};
  sensor::Sensor *left_pwm_sensor_{nullptr}, *right_pwm_sensor_{nullptr};
  sensor::Sensor *battery_sensor_{nullptr}, *distance_sensor_{nullptr};
  text_sensor::TextSensor *status_sensor_{nullptr};

  bool armed_{false};
  float throttle_{0.0f}, steer_{0.0f};
  int left_pwm_{0}, right_pwm_{0};
  uint32_t last_command_ms_{0}, last_telemetry_ms_{0};
  String input_line_;
  static constexpr uint32_t COMMAND_TIMEOUT_MS = 700;
  static constexpr uint32_t TELEMETRY_INTERVAL_MS = 200;
};

}  // namespace robot007_spp
}  // namespace esphome
