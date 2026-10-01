<div align="center">

# 🎯 ProConn Mobile

**The native Android companion for controller-based mobile gaming.**

A floating, vsync-synced crosshair overlay with a full designer, controller-driven ADS effects,
and a precision controller tuner — built for players who want a competitive edge without
touching the game's files.

[![Release](https://img.shields.io/github/v/release/tjcrims0nx/proconn-mobile?color=8B5CF6&style=for-the-badge)](https://github.com/tjcrims0nx/proconn-mobile/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-34D399?style=for-the-badge)](https://github.com/tjcrims0nx/proconn-mobile)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7C6CF0?style=for-the-badge)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Native%20APK-8B5CF6?style=for-the-badge)](https://github.com/tjcrims0nx/proconn-mobile)

[Download the latest APK](https://github.com/tjcrims0nx/proconn-mobile/releases/latest)

</div>

---

## ✨ Features

### Crosshair Overlay
- **Floating crosshair** rendered with vsync-aligned drawing — smooth at any refresh rate
- **Full designer**: style, size, thickness, color, opacity, dot, outline, and gap controls
- **Live animated preview** of every change before it goes on screen
- **My Designs** — save, rename, and reload your favorite setups
- **Drag-to-position**: one switch unlocks drag placement; release to save and lock it down
- Overlay is **non-touchable and non-focusable** during gameplay — it never steals input

### ADS Effects
- **Shrink on ADS** — the crosshair tightens to 65% while aiming and springs back on release
- **Pulse effects** `BETA` — a subtle breathing pulse that runs only while ADS is active,
  tightening together with the ADS shrink, just like the desktop ProConn pulse

### Controller ADS Sync
- **Learn flow**: tap LEARN, press your controller's ADS button — the app mirrors it into the
  crosshair's ADS state with zero in-game buttons and zero remapping
- Hold to shrink + pulse, release to spring back — following your real aim
- Implemented via an accessibility key-event filter that **never consumes input**:
  the game receives every press untouched

### Controller Tuner
- **Stick visualizer** with live position tracking
- **Deadzone measurement** and manual deadzone / damping tuning
- **Response curves** — including a Dynamic curve — with a live curve graph
- **Aim dial** and **button tester** (sticks, d-pad, triggers, face buttons)
- **APPLY BEST AIM SETTINGS** — one-tap pro baseline for aim tuning, now **game-aware**:
  the app detects your foreground game and applies its profile
- **Game detection** — auto-detects CODM, PUBG Mobile, Bloodstrike, Fortnite, and
  Destiny Rising (via the accessibility service), with manual override chips and
  per-game tuning profiles (deadzone, damping, response curve, aim dial)
- **PRO SETTINGS checklist** — the recommended in-game settings to pair with the app

### Bluetooth
- In-app pairing via CompanionDeviceManager with live connection status
- Bonded-device list with Connected / Paired badges

---

## 📲 Installation

1. Download the latest APK from the [**Releases**](https://github.com/tjcrims0nx/proconn-mobile/releases/latest) page.
2. Open it on your Android device (Android 8.0+) and allow *Install unknown apps* when prompted.
3. Open **ProConn Mobile** and follow the in-app permissions checklist:
   - **Display over other apps** — lets the crosshair float over your game
   - **Accessibility service** — powers Controller ADS sync (key-event observation only)
   - **Bluetooth** — for controller pairing and status

> Upgrades install directly over previous versions — your designs and settings are kept.

## 🚀 Quick Start

1. **Crosshair tab** → design your crosshair in the live preview.
2. Press **START** — the overlay goes live as a foreground service.
3. Enable **Controller ADS sync**, tap **LEARN**, and press your controller's ADS button.
4. Launch your game. Hold ADS → crosshair shrinks and pulses. Release → it springs back.

---

## 🛠 Build from Source

No Gradle required — the project builds with a direct `aapt2` / `kotlinc` / `d8` pipeline:

```bash
# 1. Compile resources
aapt2 compile --dir app/src/main/res -o compiled_res.zip

# 2. Link (manifest must carry an explicit package= attribute)
aapt2 link -o app-base.apk -I android.jar \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 14 --version-name 2.3 \
  --java gen/ compiled_res.zip

# 3. Compile Kotlin
kotlinc $(find app/src/main/java gen -name "*.kt") \
  -cp android.jar -d classes/ -jvm-target 1.8

# 4. Dex
d8 --lib android.jar --min-api 26 \
  --output dex/ classes.jar kotlin-stdlib.jar

# 5. Assemble, align, sign (v2+v3 — v1 is rejected at targetSdk 34)
#    insert classes.dex at the APK root, then:
zipalign -f 4 app.apk app-aligned.apk
apksigner sign --ks <keystore> app-aligned.apk
```

**Toolchain:** JDK 17 · Kotlin 1.9.24 · Android build-tools 34.0.0 · platform android-35
**Package:** `com.proconn.mobile` · **minSdk** 26 · **targetSdk** 34

---

## 📁 Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/proconn/mobile/
│   ├── MainActivity.kt                 # Tab host (Crosshair / Tuner / Guide)
│   ├── fragments/
│   │   ├── CrosshairFragment.kt        # Designer, ADS effects, controller sync
│   │   ├── TunerFragment.kt            # Sticks, curves, aim tuning, Bluetooth
│   │   └── GuideFragment.kt            # Setup guide + permissions checklist
│   ├── service/
│   │   ├── OverlayService.kt           # Foreground overlay: crosshair + ADS state
│   │   └── TapAccessibilityService.kt  # Key-event filter for controller sync
│   ├── store/                          # Persisted prefs (designs, overlay, sync)
│   └── ui/
│       ├── CrosshairView.kt            # Vsync crosshair renderer
│       ├── DynamicEffects.kt           # Shrink + pulse animation math
│       ├── OverlayView.kt
│       ├── StickView.kt                # Stick visualizer
│       └── CurveView.kt                # Response-curve graph
└── res/                                # Purple enterprise design system
    ├── drawable/                       # Gradient cards, pills, buttons
    ├── layout/
    ├── values/                         # Colors, styles, strings
    └── xml/accessibility_service_config.xml
```

---

## 🔐 Permissions — Why Each Exists

| Permission | Used for |
|---|---|
| Display over other apps | Floating the crosshair above your game |
| Accessibility service | Observing controller button key-events for ADS sync |
| Bluetooth / CompanionDeviceManager | Controller pairing and connection status |
| Foreground service + notification | Keeping the overlay alive while you play |

ProConn Mobile contains **no ads, no analytics, and no network calls**. Everything runs on-device.

## 🗺 Roadmap

- Pulse effects graduation from `BETA`
- More crosshair styles and animation options
- Per-game overlay profiles

---

<div align="center">

Built for players who tune everything. 💜

</div>
