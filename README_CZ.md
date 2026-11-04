# Robot Cockpit / Robot007 — sjednocený projekt

Hlavní repozitář pro robotickou platformu ESP32 a Android aplikaci Robot Cockpit. Android aplikace je v Kotlinu + Jetpack Compose.

## Jednotná struktura

- `app/` — Android aplikace: celoplošný dashboard, joystick, SPP ovládání, telemetrie a samostatná obrazovka Xiaomi LDS BLE.
- `app/src/main/java/cz/robotcockpit/app/LidarBleClient.kt` — BLE skenování, GATT připojení, notifikace a sestavení 360bodového skenu z 23 fragmentů.
- `app/src/main/java/cz/robotcockpit/app/LidarActivity.kt` — živé 360° LiDAR zobrazení.
- `firmware/Robot007_SPP/` — původní Arduino firmware pro ESP32 Classic Bluetooth SPP.
- `esphome/` — experimentální ESPHome varianta řídicího firmware.
- `PROTOCOL_SPP.md` — JSON protokol mezi Androidem a Robot007 přes Bluetooth Classic SPP.
- `.github/workflows/build-apk.yml` — automatické sestavení debug APK po pushi do main.

## Komunikace

- **Robot drive / telemetry:** Bluetooth Classic SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`.
- **Xiaomi LDS bridge:** samostatné Bluetooth LE GATT spojení; service UUID `a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10`, data characteristic `a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10`.
- LDS stream používá 20B rámce s hlavičkou `D3`, 23 fragmentů na otáčku a 360 vzdálenostních vzorků. Jeden byte vzdálenosti představuje 5 cm.

## Aktuální stav LiDAR části

Aktuálně je doplněno BLE vyhledávání, GATT připojení, odběr notifikací, skládání 23 fragmentů, živé vykreslení 360° skenu a 2D occupancy grid s rozlišením 5 cm. Mapa přijímá polohu z volitelných polí SPP telemetrie (`pose.xCm`, `pose.yCm`, `pose.yawDeg`, `pose.valid`; případně ploché aliasy). Pokud je starý firmware neposílá, aplikace mapu správně ponechá ve stavu „čeká na odometrii“ a nijak si polohu nevymýšlí. Jde o mapování podle odometrie, nikoli dokončený SLAM: další etapou je skutečný odhad pohybu z enkodérů + IMU, scan matching a korekce driftu / loop closure.

## Sestavení APK

1. Otevři záložku Actions v tomto repozitáři.
2. Vyber poslední úspěšný běh **Build Robot Cockpit APK**.
3. Stáhni artefakt `RobotCockpit-debug-APK`.

## Bezpečnost

- Výstupy motorů ve firmware zůstávají vypnuté, dokud není ověřen konkrétní driver, GPIO a zapojení.
- STOP v Androidu není bezpečnostní hardwarový vypínač.
- Před prvním testem zvedni hnací kola a ověř watchdog i fyzické odpojení napájení.
- BLE LiDAR je samostatná linka a nemění bezpečnostní stav SPP řízení.
