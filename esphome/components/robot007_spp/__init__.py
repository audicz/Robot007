import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import binary_sensor, sensor, text_sensor
from esphome.const import CONF_ID

DEPENDENCIES = ["esp32"]
AUTO_LOAD = ["binary_sensor", "sensor", "text_sensor"]

robot007_spp_ns = cg.esphome_ns.namespace("robot007_spp")
Robot007SPP = robot007_spp_ns.class_("Robot007SPP", cg.Component)

CONF_DEVICE_NAME = "device_name"
CONF_ENABLE_MOTOR_OUTPUTS = "enable_motor_outputs"
CONF_LEFT_PWM_PIN = "left_pwm_pin"
CONF_LEFT_IN1_PIN = "left_in1_pin"
CONF_LEFT_IN2_PIN = "left_in2_pin"
CONF_RIGHT_PWM_PIN = "right_pwm_pin"
CONF_RIGHT_IN1_PIN = "right_in1_pin"
CONF_RIGHT_IN2_PIN = "right_in2_pin"
CONF_BATTERY_ADC_PIN = "battery_adc_pin"
CONF_ULTRASONIC_TRIG_PIN = "ultrasonic_trig_pin"
CONF_ULTRASONIC_ECHO_PIN = "ultrasonic_echo_pin"
CONF_BATTERY_DIVIDER_RATIO = "battery_divider_ratio"
CONF_ARMED = "armed"
CONF_LEFT_PWM = "left_pwm"
CONF_RIGHT_PWM = "right_pwm"
CONF_BATTERY_VOLTAGE = "battery_voltage"
CONF_DISTANCE = "distance"
CONF_STATUS = "status"

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(Robot007SPP),
        cv.Optional(CONF_DEVICE_NAME, default="Robot007"): cv.string,
        cv.Optional(CONF_ENABLE_MOTOR_OUTPUTS, default=False): cv.boolean,
        cv.Optional(CONF_LEFT_PWM_PIN, default=25): cv.int_range(min=0, max=39),
        cv.Optional(CONF_LEFT_IN1_PIN, default=26): cv.int_range(min=0, max=39),
        cv.Optional(CONF_LEFT_IN2_PIN, default=27): cv.int_range(min=0, max=39),
        cv.Optional(CONF_RIGHT_PWM_PIN, default=14): cv.int_range(min=0, max=39),
        cv.Optional(CONF_RIGHT_IN1_PIN, default=32): cv.int_range(min=0, max=39),
        cv.Optional(CONF_RIGHT_IN2_PIN, default=33): cv.int_range(min=0, max=39),
        cv.Optional(CONF_BATTERY_ADC_PIN, default=-1): cv.int_range(min=-1, max=39),
        cv.Optional(CONF_ULTRASONIC_TRIG_PIN, default=-1): cv.int_range(min=-1, max=39),
        cv.Optional(CONF_ULTRASONIC_ECHO_PIN, default=-1): cv.int_range(min=-1, max=39),
        cv.Optional(CONF_BATTERY_DIVIDER_RATIO, default=2.0): cv.positive_float,
        cv.Optional(CONF_ARMED): binary_sensor.binary_sensor_schema(),
        cv.Optional(CONF_LEFT_PWM): sensor.sensor_schema(accuracy_decimals=0),
        cv.Optional(CONF_RIGHT_PWM): sensor.sensor_schema(accuracy_decimals=0),
        cv.Optional(CONF_BATTERY_VOLTAGE): sensor.sensor_schema(
            unit_of_measurement="V", accuracy_decimals=2, device_class="voltage",
            state_class="measurement"
        ),
        cv.Optional(CONF_DISTANCE): sensor.sensor_schema(
            unit_of_measurement="cm", accuracy_decimals=1, state_class="measurement"
        ),
        cv.Optional(CONF_STATUS): text_sensor.text_sensor_schema(),
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    cg.add(var.set_device_name(config[CONF_DEVICE_NAME]))
    cg.add(var.set_enable_motor_outputs(config[CONF_ENABLE_MOTOR_OUTPUTS]))
    cg.add(var.set_left_pins(config[CONF_LEFT_PWM_PIN], config[CONF_LEFT_IN1_PIN], config[CONF_LEFT_IN2_PIN]))
    cg.add(var.set_right_pins(config[CONF_RIGHT_PWM_PIN], config[CONF_RIGHT_IN1_PIN], config[CONF_RIGHT_IN2_PIN]))
    cg.add(var.set_sensor_pins(
        config[CONF_BATTERY_ADC_PIN],
        config[CONF_ULTRASONIC_TRIG_PIN],
        config[CONF_ULTRASONIC_ECHO_PIN],
        config[CONF_BATTERY_DIVIDER_RATIO],
    ))

    if CONF_ARMED in config:
        sens = await binary_sensor.new_binary_sensor(config[CONF_ARMED])
        cg.add(var.set_armed_sensor(sens))
    if CONF_LEFT_PWM in config:
        sens = await sensor.new_sensor(config[CONF_LEFT_PWM])
        cg.add(var.set_left_pwm_sensor(sens))
    if CONF_RIGHT_PWM in config:
        sens = await sensor.new_sensor(config[CONF_RIGHT_PWM])
        cg.add(var.set_right_pwm_sensor(sens))
    if CONF_BATTERY_VOLTAGE in config:
        sens = await sensor.new_sensor(config[CONF_BATTERY_VOLTAGE])
        cg.add(var.set_battery_sensor(sens))
    if CONF_DISTANCE in config:
        sens = await sensor.new_sensor(config[CONF_DISTANCE])
        cg.add(var.set_distance_sensor(sens))
    if CONF_STATUS in config:
        sens = await text_sensor.new_text_sensor(config[CONF_STATUS])
        cg.add(var.set_status_sensor(sens))
