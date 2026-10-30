#include "robot007_spp.h"
#include "esphome/core/log.h"

#include <cmath>
#include <cstdlib>

namespace esphome {
namespace robot007_spp {

static const char *const TAG = "robot007_spp";
static const char *const FW_VERSION = "Robot007-ESPHome-0.1";

void Robot007SPP::setup() {
  input_line_.reserve(512);
  if (enable_motor_outputs_) {
    pinMode(left_pwm_pin_, OUTPUT);
    pinMode(left_in1_pin_, OUTPUT);
    pinMode(left_in2_pin_, OUTPUT);
    pinMode(right_pwm_pin_, OUTPUT);
    pinMode(right_in1_pin_, OUTPUT);
    pinMode(right_in2_pin_, OUTPUT);
    analogWriteResolution(left_pwm_pin_, 8);
    analogWriteFrequency(left_pwm_pin_, 5000);
    analogWriteResolution(right_pwm_pin_, 8);
    analogWriteFrequency(right_pwm_pin_, 5000);
    apply_motor_output_();
  }

  if (battery_adc_pin_ >= 0) {
    pinMode(battery_adc_pin_, INPUT);
    analogReadResolution(12);
  }
  if (ultrasonic_trig_pin_ >= 0 && ultrasonic_echo_pin_ >= 0) {
    pinMode(ultrasonic_trig_pin_, OUTPUT);
    pinMode(ultrasonic_echo_pin_, INPUT);
    digitalWrite(ultrasonic_trig_pin_, LOW);
  }

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED) || !defined(CONFIG_BT_SPP_ENABLED)
  ESP_LOGE(TAG, "Bluetooth Classic SPP unavailable. Select original ESP32 (e.g. ESP32-WROOM-32) with Arduino framework.");
  if (status_sensor_ != nullptr) status_sensor_->publish_state("ERROR: SPP unsupported");
#else
  if (!serial_bt_.begin(device_name_.c_str())) {
    ESP_LOGE(TAG, "Could not start Bluetooth SPP");
    if (status_sensor_ != nullptr) status_sensor_->publish_state("ERROR: SPP start");
  } else {
    ESP_LOGI(TAG, "Bluetooth Classic SPP started; device name: %s", device_name_.c_str());
    if (status_sensor_ != nullptr) status_sensor_->publish_state("SPP ready");
  }
#endif
  last_command_ms_ = millis();
}

void Robot007SPP::dump_config() {
  ESP_LOGCONFIG(TAG, "Robot007 Bluetooth Classic SPP");
  ESP_LOGCONFIG(TAG, "  Device name: %s", device_name_.c_str());
  ESP_LOGCONFIG(TAG, "  Motor outputs enabled: %s", enable_motor_outputs_ ? "YES" : "NO");
  ESP_LOGCONFIG(TAG, "  Command watchdog: %u ms", COMMAND_TIMEOUT_MS);
  ESP_LOGCONFIG(TAG, "  Telemetry interval: %u ms", TELEMETRY_INTERVAL_MS);
  if (!enable_motor_outputs_) ESP_LOGW(TAG, "Motor outputs are DISABLED in YAML. SPP commands will not drive GPIO outputs.");
}

float Robot007SPP::read_number_(const String &line, const char *key, float fallback) {
  String token = String("\"") + key + "\"";
  int p = line.indexOf(token);
  if (p < 0) return fallback;
  p = line.indexOf(':', p + token.length());
  if (p < 0) return fallback;
  const char *start = line.c_str() + p + 1;
  while (*start == ' ' || *start == '\t') ++start;
  char *end = nullptr;
  double value = strtod(start, &end);
  if (end == start || !std::isfinite(value)) return fallback;
  return static_cast<float>(value);
}

bool Robot007SPP::read_bool_(const String &line, const char *key, bool fallback) {
  String token = String("\"") + key + "\"";
  int p = line.indexOf(token);
  if (p < 0) return fallback;
  p = line.indexOf(':', p + token.length());
  if (p < 0) return fallback;
  String value = line.substring(p + 1);
  value.trim();
  if (value.startsWith("true")) return true;
  if (value.startsWith("false")) return false;
  return fallback;
}

String Robot007SPP::read_type_(const String &line) {
  const String token = "\"type\"";
  int p = line.indexOf(token);
  if (p < 0) return "";
  p = line.indexOf(':', p + token.length());
  if (p < 0) return "";
  int first = line.indexOf('"', p + 1);
  if (first < 0) return "";
  int last = line.indexOf('"', first + 1);
  if (last < 0) return "";
  return line.substring(first + 1, last);
}

void Robot007SPP::drive_one_motor_(int pwm_pin, int in1_pin, int in2_pin, int signed_pwm) {
  const int magnitude = abs(signed_pwm);
  if (signed_pwm > 0) {
    digitalWrite(in1_pin, HIGH);
    digitalWrite(in2_pin, LOW);
  } else if (signed_pwm < 0) {
    digitalWrite(in1_pin, LOW);
    digitalWrite(in2_pin, HIGH);
  } else {
    digitalWrite(in1_pin, LOW);
    digitalWrite(in2_pin, LOW);
  }
  analogWrite(pwm_pin, magnitude);
}

void Robot007SPP::apply_motor_output_() {
  const float left = clamp(throttle_ + steer_, -1.0f, 1.0f);
  const float right = clamp(throttle_ - steer_, -1.0f, 1.0f);
  if (armed_ && enable_motor_outputs_) {
    left_pwm_ = static_cast<int>(lroundf(left * 255.0f));
    right_pwm_ = static_cast<int>(lroundf(right * 255.0f));
    drive_one_motor_(left_pwm_pin_, left_in1_pin_, left_in2_pin_, left_pwm_);
    drive_one_motor_(right_pwm_pin_, right_in1_pin_, right_in2_pin_, right_pwm_);
  } else {
    left_pwm_ = 0;
    right_pwm_ = 0;
    if (enable_motor_outputs_) {
      drive_one_motor_(left_pwm_pin_, left_in1_pin_, left_in2_pin_, 0);
      drive_one_motor_(right_pwm_pin_, right_in1_pin_, right_in2_pin_, 0);
    }
  }
}

void Robot007SPP::stop_robot_(const char *reason) {
  armed_ = false;
  throttle_ = 0.0f;
  steer_ = 0.0f;
  apply_motor_output_();
  ESP_LOGW(TAG, "STOP: %s", reason);
  if (status_sensor_ != nullptr) status_sensor_->publish_state(std::string("STOP: ") + reason);
}

void Robot007SPP::handle_line_(const String &line) {
  const String type = read_type_(line);
  if (type == "hello") {
    serial_bt_.printf("{\"type\":\"hello\",\"protocol\":1,\"firmware\":\"%s\"}\n", FW_VERSION);
    return;
  }
  if (type == "stop") {
    stop_robot_("app stop");
    return;
  }
  if (type == "arm") {
    armed_ = read_bool_(line, "armed", true);
    throttle_ = 0.0f;
    steer_ = 0.0f;
    last_command_ms_ = millis();
    apply_motor_output_();
    if (status_sensor_ != nullptr) status_sensor_->publish_state(armed_ ? (enable_motor_outputs_ ? "ARMED" : "ARMED (outputs disabled)") : "DISARMED");
    return;
  }
  if (type == "cmd") {
    const bool requested_armed = read_bool_(line, "armed", false);
    if (!requested_armed) {
      stop_robot_("command disarmed");
      return;
    }
    // A drive packet can never arm the robot. A separate explicit arm packet is required.
    if (!armed_) {
      throttle_ = 0.0f;
      steer_ = 0.0f;
      apply_motor_output_();
      return;
    }
    throttle_ = clamp(read_number_(line, "throttle", 0.0f), -1.0f, 1.0f);
    steer_ = clamp(read_number_(line, "steer", 0.0f), -1.0f, 1.0f);
    last_command_ms_ = millis();
    apply_motor_output_();
  }
}

float Robot007SPP::read_battery_voltage_() {
  if (battery_adc_pin_ < 0) return NAN;
  return (analogReadMilliVolts(battery_adc_pin_) / 1000.0f) * battery_divider_ratio_;
}

float Robot007SPP::read_distance_cm_() {
  if (ultrasonic_trig_pin_ < 0 || ultrasonic_echo_pin_ < 0) return NAN;
  digitalWrite(ultrasonic_trig_pin_, LOW);
  delayMicroseconds(3);
  digitalWrite(ultrasonic_trig_pin_, HIGH);
  delayMicroseconds(10);
  digitalWrite(ultrasonic_trig_pin_, LOW);
  const unsigned long duration = pulseIn(ultrasonic_echo_pin_, HIGH, 18000UL);
  if (duration == 0) return NAN;
  return duration * 0.0343f / 2.0f;
}

void Robot007SPP::publish_telemetry_() {
  const float battery = read_battery_voltage_();
  const float distance = read_distance_cm_();
  if (armed_sensor_ != nullptr) armed_sensor_->publish_state(armed_);
  if (left_pwm_sensor_ != nullptr) left_pwm_sensor_->publish_state(left_pwm_);
  if (right_pwm_sensor_ != nullptr) right_pwm_sensor_->publish_state(right_pwm_);
  if (battery_sensor_ != nullptr && std::isfinite(battery)) battery_sensor_->publish_state(battery);
  if (distance_sensor_ != nullptr && std::isfinite(distance)) distance_sensor_->publish_state(distance);

  serial_bt_.print("{\"type\":\"telemetry\",\"protocol\":1,\"firmware\":\"");
  serial_bt_.print(FW_VERSION);
  serial_bt_.print("\",\"armed\":");
  serial_bt_.print(armed_ ? "true" : "false");
  serial_bt_.print(",\"throttle\":"); serial_bt_.print(throttle_, 3);
  serial_bt_.print(",\"steer\":"); serial_bt_.print(steer_, 3);
  serial_bt_.print(",\"leftPwm\":"); serial_bt_.print(left_pwm_);
  serial_bt_.print(",\"rightPwm\":"); serial_bt_.print(right_pwm_);
  serial_bt_.print(",\"uptimeMs\":"); serial_bt_.print(millis());
  serial_bt_.print(",\"batteryV\":");
  if (!std::isfinite(battery)) serial_bt_.print("null"); else serial_bt_.print(battery, 2);
  serial_bt_.print(",\"distanceCm\":");
  if (!std::isfinite(distance)) serial_bt_.print("null"); else serial_bt_.print(distance, 1);
  serial_bt_.println("}");
}

void Robot007SPP::loop() {
  while (serial_bt_.available()) {
    const char c = static_cast<char>(serial_bt_.read());
    if (c == '\n') {
      input_line_.trim();
      if (!input_line_.isEmpty()) handle_line_(input_line_);
      input_line_ = "";
    } else if (c != '\r') {
      if (input_line_.length() < 512) input_line_ += c;
      else input_line_ = "";
    }
  }

  if (armed_ && static_cast<uint32_t>(millis() - last_command_ms_) > COMMAND_TIMEOUT_MS) {
    stop_robot_("command watchdog timeout");
  }
  if (serial_bt_.hasClient() && static_cast<uint32_t>(millis() - last_telemetry_ms_) >= TELEMETRY_INTERVAL_MS) {
    last_telemetry_ms_ = millis();
    publish_telemetry_();
  }
}

}  // namespace robot007_spp
}  // namespace esphome
