# Pocket Penguin / Penguin Room

A lightweight Android live wallpaper where an original canvas-drawn penguin lives in a tiny room.

## Requirements

- Android Studio Koala (2024.1.1) or newer
- JDK 17 or newer (JDK 21 is supported)
- Android SDK Platform 35 and Build Tools 35.0.0
- Android 6.0+ (API 23+)

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Install with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Set as wallpaper

1. Open **Pocket Penguin**.
2. Adjust the options and tap **Set live wallpaper**.
3. Confirm the Android live-wallpaper preview, then apply it to the home screen (and lock screen when the device supports it).

You can also open the system picker from the app drawer under **Live Wallpapers**.

## Features

- `WallpaperService.Engine` + Canvas rendering, capped around 30 FPS and paused when invisible.
- Autonomous state machine: walk, idle, look at clock, sleep/wake, flap, slide/fall, charging reaction, and screen-edge exit/peek/re-entry.
- Tap reaction: tap the penguin to greet it; tap elsewhere to make it look toward and walk to that point.
- Battery/charging, screen-on, and user-present signals are bridged into the wallpaper engine where Android permits them.
- Time-of-day lighting and configurable tap/clock/charging/screen-on/door reactions.
- Original vector-like artwork drawn from primitives; no third-party character assets.

## Android limitations

Live-wallpaper APIs vary by launcher and manufacturer. `ACTION_USER_PRESENT` and screen-on broadcasts are best-effort signals; some Android versions restrict or delay them. Lock-screen live wallpaper support is launcher/device dependent, so the system preview may offer only the home screen. The wallpaper does not request notification, overlay, accessibility, or wake-lock permissions.

## v0.2 interaction / character update
- Enlarged the penguin to roughly 20-30% of screen height on common phone aspect ratios.
- Enabled WallpaperService touch events explicitly with `setTouchEventsEnabled(true)`.
- Added clearer head/body/full-body tap reactions and multi-tap confusion feedback.
- Added CURIOUS, CONFUSED and SLEEPY expression states; existing clock, charging, screen-on and edge-door states are retained.
- Tap effects are intentionally larger/longer (heart, !, ?, sparkle) so feedback is visible on a home screen.
- Kept visibility-aware rendering: idle/sleep runs at a reduced cadence and rendering stops when the wallpaper is not visible.

## v0.3 character asset update
The penguin renderer now uses layered transparent PNG assets instead of constructing the whole character from Canvas primitives. Body, face, flippers, feet, and expression overlays are separate assets. This allows independent flipper rotation, alternating foot motion, body sway/bobbing, head tilt and expression overlays while preserving the Live Wallpaper state machine and touch behavior.

## v0.6 character / room update
See `CHANGELOG-v0.6.md`. Part art is regenerated with `python3 tools/make_assets.py out_dir` (needs Pillow, NumPy, SciPy); copy `pg_*.png` into `app/src/main/res/drawable-nodpi/` and `PartLayout.java` next to the sources.

## v0.6.1
See CHANGELOG-v0.6.1.md. Tip: tap the head repeatedly to stroke it; adjust "Floor height" in the app if your launcher dock overlaps the room.

## v0.7
Jinbei the whale shark joins the room. See CHANGELOG-v0.7.md. Regenerate its art with python3 tools/make_buddy_assets.py.
