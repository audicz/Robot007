# Robot Cockpit / Robot007 — sjednocený projekt

Hlavní repozitář pro robotickou platformu ESP32 a Android aplikaci Robot Cockpit. Android aplikace je v Kotlinu + Jetpack Compose.

## Jednotná struktura

- `app/` — Android aplikace: celoplošný dashboard, joystick, SPP ovládání, telemetrie a samostatná obrazovka SLAM přijímající LiDAR D9 přes Wi-Fi UDP.
- `app/src/main/java/cz/robotcockpit/app/LidarBleClient.kt` — Wi-Fi UDP příjem D9 rámců, sestavení 360bodového skenu ze 4 fragmentů a statistiky přenosu.
- `app/src/main/java/cz/robotcockpit/app/LidarActivity.kt` — živé 360° LiDAR zobrazení.
- `firmware/Robot007_SPP/` — původní Arduino firmware pro ESP32 Classic Bluetooth SPP.
- `esphome/` — experimentální ESPHome varianta řídicího firmware.
- `PROTOCOL_SPP.md` — JSON protokol mezi Androidem a Robot007 přes Bluetooth Classic SPP.
- `.github/workflows/build-apk.yml` — automatické sestavení debug APK po pushi do main.

## Komunikace

- **Robot drive / telemetry:** Bluetooth Classic SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`.
- **Xiaomi LDS bridge:** samostatné Bluetooth LE GATT spojení; service UUID `a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10`, data characteristic `a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10`.
- Mobilní SLAM přijímá z LiDAR Bridge zrcadlený Wi-Fi UDP stream na portu `18512`. Každý D9 rámec má 185 B: hlavičku, sekvenci, index fragmentu, RPM, duty a 90 vzdáleností `uint16` v milimetrech. Čtyři fragmenty tvoří 360bodový sken. ESP-NOW D9 přenos do VESC Cockpitu zůstává beze změny.

## Aktuální stav LiDAR části

Android přijímá kompletní 360° skeny přes Wi-Fi UDP a provádí LiDAR-only SLAM přímo v telefonu. Aplikace zobrazuje dobu zpracování wall/CPU, průměr a maximum, frekvenci skenů a počty přijatých, chybných a ztracených skenů. Základní lokalizace nepotřebuje enkodéry, IMU ani odometrii ESP32.

Implementovaný řetězec:
- Coarse-to-fine correlative scan-to-map tracking.
- Výběr keyframů podle ujeté vzdálenosti, změny směru a času.
- Polar range descriptor pro hledání kandidátů návratu na dříve navštívené místo.
- Point-to-point ICP pro ověření kandidátní smyčky; smyčka se přijme pouze při dostatečném překryvu a nízké chybě.
- Pose graph se sekvenčními relativními vazbami a loop-closure vazbami; po optimalizaci se mapa znovu sestaví z keyframů, aby se oprava promítla do celé mapy a trajektorie.
- Automatické ukládání a načtení occupancy mapy, keyframů a grafu do interního úložiště Androidu.

Mapa má 600 × 600 buněk, 5 cm/buňku (30 × 30 m). Žlutá čára zobrazuje trajektorii keyframů. Reset mapy smaže i uložený SLAM soubor. ESP-NOW D9 rámce a Bluetooth Classic SPP protokol v1 zůstávají beze změny. Mobilní SLAM UDP stream je pouze další kopie D9 dat; nezasahuje do řízení motorů.

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


### Export a import mapy

Na obrazovce 2D MAPA jsou akce **ULOŽIT SOUBOR** a **NAČÍST SOUBOR**. Uložení otevře systémový výběr umístění Androidu a vytvoří soubor `robot007-map.r7slam`. Soubor obsahuje occupancy grid 600 × 600 / 5 cm, aktuální pózu, trajektorii, keyframy, skeny keyframů a vazby pose graphu. Lze jej později načíst zpět přes **NAČÍST SOUBOR**. Formát je verzovaný a aplikace ověřuje jeho hlavičku, rozměry a strukturu před obnovením. Automatické interní ukládání po pěti sekundách zůstává aktivní nezávisle na exportu.


## Mobilní SLAM benchmark V24.10.22

- LDS Bridge zrcadlí každý ze čtyř D9 fragmentů (185 B) také přes UDP broadcast `255.255.255.255:18512`; původní ESP-NOW přenos do Cockpitu se nemění.
- Aplikace sestaví 360 rozsahů v mm, předá sken do `LidarSlamGrid` a zobrazí wall time, CPU time, EMA průměr, maximum, scan Hz a statistiku UDP paketů/ztrát.
- Jde o paralelní výpočetní benchmark, nikoli o přepnutí řízení robota na telefon. Telefon zatím neposílá motorové povely ani nepřebírá bezpečnostní watchdog.
- Pro porovnání použij stejnou trasu, stejnou rychlost LDS, stejné prostředí a stejné napájení. Zaznamenej mapu a metriky nejprve s SLAM v Cockpitu, potom v telefonu.
