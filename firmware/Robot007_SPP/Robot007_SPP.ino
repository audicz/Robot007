/*
 * Robot007 — ESP32 Bluetooth Classic SPP firmware
 * Protocol: newline-delimited JSON, see PROTOCOL_SPP.md
 *
 * SAFETY: motor outputs are disabled by default. Confirm the actual motor driver,
 * wiring and GPIO mapping before setting ENABLE_MOTOR_OUTPUTS to true.
 *
 * BluetoothSerial / SPP requires the original ESP32 (e.g. ESP32-WROOM-32).
 * It is not supported by ESP32-S2, ESP32-S3 or ESP32-C3.
 */
#include <Arduino.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled in this ESP32 build. Select a classic ESP32 target.
#endif
#if !defined(CONFIG_BT_SPP_ENABLED)
#error Bluetooth SPP is not available on this target.
#endif

BluetoothSerial SerialBT;

static const char *DEVICE_NAME = "Robot007";
static const char *FIRMWARE_VERSION = "Robot007-SPP-0.3";
static const bool ENABLE_MOTOR_OUTPUTS = false; // Change only after checking the driver and pins.

// Example pin mapping for a dual H-bridge: PWM + two direction pins per motor.
// These are examples, not a claim about the user's hardware.
static const int LEFT_PWM_PIN = 25;
static const int LEFT_IN1_PIN = 26;
static const int LEFT_IN2_PIN = 27;
static const int RIGHT_PWM_PIN = 14;
static const int RIGHT_IN1_PIN = 32;
static const int RIGHT_IN2_PIN = 33;

// Optional sensors. Set to real pins after wiring; -1 means disabled.
// Battery ADC needs a correctly designed resistor divider and calibration.
// Ultrasonic ECHO must not exceed 3.3 V at the ESP32 GPIO.
static const int BATTERY_ADC_PIN = -1;
static const int ULTRASONIC_TRIG_PIN = -1;
static const int ULTRASONIC_ECHO_PIN = -1;
static const float BATTERY_DIVIDER_RATIO = 2.0f; // Example only; calibrate for your divider.
static const float ADC_REFERENCE_V = 3.3f;

static const uint32_t TELEMETRY_INTERVAL_MS = 200;
static const uint32_t COMMAND_TIMEOUT_MS = 700;

bool armed = false;
float throttleCommand = 0.0f;
float steerCommand = 0.0f;
int leftPwm = 0;
int rightPwm = 0;
uint32_t lastCommandMs = 0;
uint32_t lastTelemetryMs = 0;
String inputLine;

static float readNumber(const String &line, const char *key, float fallback) {
  String token = String("\"") + key + "\"";
  int p = line.indexOf(token);
  if (p < 0) return fallback;
  p = line.indexOf(':', p + token.length());
  if (p < 0) return fallback;
  const char *start = line.c_str() + p + 1;
  while (*start == ' ' || *start == '\t') ++start;
  char *end = nullptr;
  double value = strtod(start, &end);
  if (end == start || !isfinite(value)) return fallback;
  return (float)value;
}

static bool readBool(const String &line, const char *key, bool fallback) {
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

static String readType(const String &line) {
  String token = "\"type\"";
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

static int toPwm(float value) {
  value = constrain(value, -1.0f, 1.0f);
  return (int)roundf(value * 255.0f);
}

static void driveOneMotor(int pwmPin, int in1, int in2, int signedPwm) {
  int magnitude = abs(signedPwm);
  if (signedPwm > 0) {
    digitalWrite(in1, HIGH); digitalWrite(in2, LOW);
  } else if (signedPwm < 0) {
    digitalWrite(in1, LOW); digitalWrite(in2, HIGH);
  } else {
    digitalWrite(in1, LOW); digitalWrite(in2, LOW);
  }
  analogWrite(pwmPin, magnitude);
}

static void applyMotorOutput() {
  float left = constrain(throttleCommand + steerCommand, -1.0f, 1.0f);
  float right = constrain(throttleCommand - steerCommand, -1.0f, 1.0f);
  leftPwm = armed ? toPwm(left) : 0;
  rightPwm = armed ? toPwm(right) : 0;

  if (ENABLE_MOTOR_OUTPUTS) {
    driveOneMotor(LEFT_PWM_PIN, LEFT_IN1_PIN, LEFT_IN2_PIN, leftPwm);
    driveOneMotor(RIGHT_PWM_PIN, RIGHT_IN1_PIN, RIGHT_IN2_PIN, rightPwm);
  }
}

static void stopRobot(const char *reason) {
  armed = false;
  throttleCommand = 0.0f;
  steerCommand = 0.0f;
  applyMotorOutput();
  Serial.printf("STOP: %s\n", reason);
}

static void handleCommand(const String &line) {
  String type = readType(line);
  if (type == "hello") {
    SerialBT.printf("{\"type\":\"hello\",\"protocol\":1,\"firmware\":\"%s\"}\n", FIRMWARE_VERSION);
    return;
  }
  if (type == "stop") {
    stopRobot("app stop");
    return;
  }
  if (type == "arm") {
    armed = readBool(line, "armed", true);
    throttleCommand = 0.0f;
    steerCommand = 0.0f;
    lastCommandMs = millis();
    applyMotorOutput();
    return;
  }
  if (type == "cmd") {
    bool requestedArmed = readBool(line, "armed", false);
    if (!requestedArmed) {
      stopRobot("command disarmed");
      return;
    }
    // Commands refresh the watchdog. An explicit arm packet is also required
    // by the app before its local controls will send nonzero commands.
    armed = true;
    throttleCommand = constrain(readNumber(line, "throttle", 0.0f), -1.0f, 1.0f);
    steerCommand = constrain(readNumber(line, "steer", 0.0f), -1.0f, 1.0f);
    lastCommandMs = millis();
    applyMotorOutput();
  }
}

static float readBatteryVoltage() {
  if (BATTERY_ADC_PIN < 0) return NAN;
  uint32_t mv = analogReadMilliVolts(BATTERY_ADC_PIN);
  return (mv / 1000.0f) * BATTERY_DIVIDER_RATIO;
}

static float readDistanceCm() {
  if (ULTRASONIC_TRIG_PIN < 0 || ULTRASONIC_ECHO_PIN < 0) return NAN;
  digitalWrite(ULTRASONIC_TRIG_PIN, LOW);
  delayMicroseconds(3);
  digitalWrite(ULTRASONIC_TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(ULTRASONIC_TRIG_PIN, LOW);
  unsigned long duration = pulseIn(ULTRASONIC_ECHO_PIN, HIGH, 18000UL);
  if (duration == 0) return NAN;
  return duration * 0.0343f / 2.0f;
}

static void sendTelemetry() {
  float battery = readBatteryVoltage();
  float distance = readDistanceCm();
  SerialBT.print("{\"type\":\"telemetry\",\"protocol\":1");
  SerialBT.print(",\"firmware\":\""); SerialBT.print(FIRMWARE_VERSION); SerialBT.print("\"");
  SerialBT.print(",\"armed\":"); SerialBT.print(armed ? "true" : "false");
  SerialBT.print(",\"throttle\":"); SerialBT.print(throttleCommand, 3);
  SerialBT.print(",\"steer\":"); SerialBT.print(steerCommand, 3);
  SerialBT.print(",\"leftPwm\":"); SerialBT.print(leftPwm);
  SerialBT.print(",\"rightPwm\":"); SerialBT.print(rightPwm);
  SerialBT.print(",\"uptimeMs\":"); SerialBT.print(millis());
  SerialBT.print(",\"batteryV\":");
  if (isnan(battery)) SerialBT.print("null"); else SerialBT.print(battery, 2);
  SerialBT.print(",\"distanceCm\":");
  if (isnan(distance)) SerialBT.print("null"); else SerialBT.print(distance, 1);
  SerialBT.println("}");
}

void setup() {
  Serial.begin(115200);
  inputLine.reserve(256);

  if (ENABLE_MOTOR_OUTPUTS) {
    pinMode(LEFT_PWM_PIN, OUTPUT); pinMode(LEFT_IN1_PIN, OUTPUT); pinMode(LEFT_IN2_PIN, OUTPUT);
    pinMode(RIGHT_PWM_PIN, OUTPUT); pinMode(RIGHT_IN1_PIN, OUTPUT); pinMode(RIGHT_IN2_PIN, OUTPUT);
    analogWriteResolution(LEFT_PWM_PIN, 8); analogWriteFrequency(LEFT_PWM_PIN, 5000);
    analogWriteResolution(RIGHT_PWM_PIN, 8); analogWriteFrequency(RIGHT_PWM_PIN, 5000);
    applyMotorOutput();
  }
  if (BATTERY_ADC_PIN >= 0) { pinMode(BATTERY_ADC_PIN, INPUT); analogReadResolution(12); }
  if (ULTRASONIC_TRIG_PIN >= 0 && ULTRASONIC_ECHO_PIN >= 0) {
    pinMode(ULTRASONIC_TRIG_PIN, OUTPUT); pinMode(ULTRASONIC_ECHO_PIN, INPUT);
  }

  if (!SerialBT.begin(DEVICE_NAME)) {
    Serial.println("Bluetooth SPP start failed");
  } else {
    Serial.printf("%s ready; SPP name: %s\n", FIRMWARE_VERSION, DEVICE_NAME);
  }
  lastCommandMs = millis();
}

void loop() {
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      inputLine.trim();
      if (!inputLine.isEmpty()) handleCommand(inputLine);
      inputLine = "";
    } else if (c != '\r') {
      if (inputLine.length() < 512) inputLine += c;
      else inputLine = "";
    }
  }

  if (armed && (uint32_t)(millis() - lastCommandMs) > COMMAND_TIMEOUT_MS) {
    stopRobot("command watchdog timeout");
  }
  if ((uint32_t)(millis() - lastTelemetryMs) >= TELEMETRY_INTERVAL_MS) {
    lastTelemetryMs = millis();
    if (SerialBT.hasClient()) sendTelemetry();
  }
}
