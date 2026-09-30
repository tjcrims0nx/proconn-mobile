# ProConn Mobile

Native Android companion app for controller-based mobile gaming (e.g. CODM).

- Floating vsync-synced crosshair overlay with designer + saved designs
- Controller ADS sync (accessibility key-event Learn flow) driving ADS shrink + pulse effects
- Controller tuner: stick visualizer, deadzone measurement, response curves, aim dial, button tester
- Bluetooth pairing via CompanionDeviceManager

## Build

Manual Gradle-free pipeline (see `~/TOOLS.md` in the dev environment):

1. `aapt2 compile --dir app/src/main/res -o compiled_res.zip`
2. `aapt2 link -o app-base.apk -I android.jar --manifest <manifest> --min-sdk-version 26 --target-sdk-version 34 --version-code N --version-name X.Y --java gen/ compiled_res.zip`
3. `kotlinc <sources> <gen R.java> -cp android.jar -d classes/ -jvm-target 1.8`
4. `d8 --lib android.jar --min-api 26 --output dex/ classes.jar kotlin-stdlib.jar`
5. Insert `classes.dex` at APK root, `zipalign -f 4`, `apksigner sign` (v2+v3)

Package: `com.proconn.mobile` · minSdk 26 · targetSdk 34
