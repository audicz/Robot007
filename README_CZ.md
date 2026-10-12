# Robot Cockpit – Android projekt

ZIP obsahuje Android projekt a GitHub Actions workflow pro sestavení APK.

1. Rozbal ZIP.
2. Nahraj obsah složky `RobotCockpit_APK_Build` do kořene repozitáře `audicz/rbot`, se sloučením složek a přepsáním odpovídajících souborů.
3. Zkontroluj, že workflow je v `.github/workflows/build-apk.yml`.
4. Na GitHubu otevři **Actions** a vyber **Build Robot Cockpit APK**.
5. Po úspěšném běhu stáhni artifact `RobotCockpit-debug-APK`.

Poznámka: aplikace je zatím prototyp; živé připojení k robotovi/ESP32 není implementováno.
