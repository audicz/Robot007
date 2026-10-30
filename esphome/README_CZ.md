# Robot007 – ESPHome + Bluetooth Classic SPP

Tato varianta nahrazuje původní samostatný Arduino firmware integrací ESPHome. ESPHome zajišťuje Wi‑Fi, Home Assistant API a OTA aktualizace; externí komponenta `robot007_spp` zachovává protokol JSON po řádcích a Bluetooth Classic SPP pro stávající Android aplikaci.

## Důležité před instalací

- Je nutný původní ESP32 s Bluetooth Classic (např. ESP32-WROOM-32 / ESP32 DevKit). ESP32-S2, ESP32-S3 a ESP32-C3 Bluetooth Classic SPP nepodporují.
- Výchozí nastavení má `enable_motor_outputs: false`. Robot nebude z těchto GPIO řídit motory, dokud nebude ověřen typ driveru, skutečné zapojení a čísla pinů.
- Piny 25/26/27 a 14/32/33 jsou pouze příklad původního firmware, nikoli ověřené zapojení tvého robota.
- Pro Home Assistant je potřeba nastavit Wi‑Fi tajné údaje.
- ESPHome OTA a API jsou dostupné přes Wi‑Fi. Android kokpit nadále komunikuje přes Bluetooth Classic SPP, nikoli přes ESPHome API.

## Instalace

1. Zkopíruj adresář `esphome/` do konfigurace ESPHome tak, aby soubor byl například `/config/esphome/robot007.yaml` a komponenta v `/config/esphome/components/robot007_spp/`.
2. V souboru `secrets.yaml` nastav:
   ```yaml
   wifi_ssid: "název Wi-Fi"
   wifi_password: "heslo k Wi-Fi"
   robot007_fallback_password: "dlouhé-heslo-pro-nouzový-AP"
   ```
3. Otevři `robot007.yaml` v ESPHome Dashboardu a nejdřív spusť **Validate** a **Install**.
4. Spáruj telefon s Bluetooth zařízením `Robot007`. Android aplikace používá protokol SPP v1, řádkově oddělené JSON zprávy.
5. V Home Assistantu se objeví stav SPP, povolení pohonu a diagnostické senzory.

## Senzory

- Napětí baterie je vypnuté (`battery_adc_pin: -1`) do nastavení správného děliče a ADC pinu.
- Ultrazvuk je vypnutý (`ultrasonic_*_pin: -1`) do ověření typu senzoru a bezpečné úrovně ECHO 3,3 V.
- GPS zatím není v této komponentě implementována. Mapa v Android aplikaci nedostane polohu, dokud nebude připojen GPS přijímač a doplněna telemetrie o `latitude`/`longitude`.

## Bezpečnost

- Nezapínej `enable_motor_outputs` pouze na základě výchozích pinů. Nejdřív potvrď přesný model ESP32, typ motorového driveru a zapojení.
- Testuj s koly ve vzduchu a s dostupným fyzickým odpojením napájení motorů.
- Watchdog zastaví pohon po 700 ms bez platného jízdního povelu.
- Softwarový STOP není bezpečnostní nouzové zastavení.
