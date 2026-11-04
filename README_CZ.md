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

Android přijímá kompletní 360° skeny přes BLE a provádí LiDAR-only SLAM přímo v telefonu; základní lokalizace nepotřebuje enkodéry, IMU ani odometrii ESP32.

Implementovaný řetězec:
- Coarse-to-fine correlative scan-to-map tracking.
- Výběr keyframů podle ujeté vzdálenosti, změny směru a času.
- Polar range descriptor pro hledání kandidátů návratu na dříve navštívené místo.
- Point-to-point ICP pro ověření kandidátní smyčky; smyčka se přijme pouze při dostatečném překryvu a nízké chybě.
- Pose graph se sekvenčními relativními vazbami a loop-closure vazbami; po optimalizaci se mapa znovu sestaví z keyframů, aby se oprava promítla do celé mapy a trajektorie.
- Automatické ukládání a načtení occupancy mapy, keyframů a grafu do interního úložiště Androidu.

Mapa má 600 × 600 buněk, 5 cm/buňku (30 × 30 m). Žlutá čára zobrazuje trajektorii keyframů. Reset mapy smaže i uložený SLAM soubor. BLE UUID, 20B fragmenty, 23 fragmentů na otáčku a Bluetooth Classic SPP protokol v1 zůstávají beze změny.

**Technické omezení:** Jde o vlastní odlehčený 2D SLAM pro daný LDS bridge, ne o plnou implementaci Cartographeru. Optimalizace grafu je iterativní relaxace, ne sparse nonlinear least-squares solver; loop closure závisí na geometrii prostředí a musí se ověřit jízdním testem. Kvalitu mapy nelze potvrdit samotným sestavením APK.

## Sestavení APK

1. Otevři záložku Actions v tomto repozitáři.
2. Vyber poslední úspěšný běh **Build Robot Cockpit APK**.
3. Stáhni artefakt `RobotCockpit-debug-APK`.

## Bezpečnost

- Výstupy motorů ve firmware zůstávají vypnuté, dokud není ověřen konkrétní driver, GPIO a zapojení.
- STOP v Androidu není bezpečnostní hardwarový vypínač.
- Před prvním testem zvedni hnací kola a ověř watchdog i fyzické odpojení napájení.
- BLE LiDAR je samostatná linka a nemění bezpečnostní stav SPP řízení.
