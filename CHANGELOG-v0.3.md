# Pocket Penguin v0.3

## Character renderer
- Replaced the monolithic Canvas penguin with layered transparent PNG assets.
- Added separate body, face, left/right flipper, left/right foot and expression overlay assets.
- Increased character target size to roughly 29% of screen height, clamped for practical phone sizes.
- Added independent part transforms for waddling body sway, vertical bob, alternating feet, flipper motion, jump and head tilt.
- Preserved the existing WallpaperService and state machine.

## Existing interactions retained
- Head/body/general tap reactions and multi-tap reaction.
- Sleep wake-up tap sequence.
- Tap-away curiosity/approach behavior.
- Clock look-up behavior.
- Charging reaction and run-to-charger behavior.
- Screen-on/user-present wake reaction.
- Exit/off-screen/peek/re-entry behavior.

## Build status
`./gradlew assembleDebug` was attempted. The source reached the Gradle wrapper bootstrap step, but this execution environment cannot resolve services.gradle.org, so Gradle 8.7 could not be downloaded. No Java/Android compilation result was available in this environment.
