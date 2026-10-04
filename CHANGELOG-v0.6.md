# Pocket Penguin v0.6 - "A penguin that lives here"

Existing project upgraded in place (same package, same WallpaperService, same prefs keys). Nothing was rebuilt from scratch.

## Character
- New original, round "pet" penguin drawn as 8 separate part PNGs (head, body, beak, 2 wings, 2 feet, tail); generator in `tools/make_assets.py`.
- Eyes / eyelids / brows / mouth / cheeks are drawn at runtime on the head, so expressions are continuous, not swapped images.
- Expressions: NORMAL, HAPPY, VERY_HAPPY, CURIOUS, SURPRISED, SLEEPY, SLEEPING, CONFUSED, EXCITED, SHY (+ DAZE).

## Animation (`Rig`)
- Every part follows a target through its own damped spring: head lags body, wings lag head, landings overshoot.
- Waddle: weight shift -> body lean -> late head -> alternating feet -> belly bounce; eased start / stop.
- Jump = crouch -> wings open -> launch -> lagging wings -> landing squash -> bounce.
- Squash & stretch on jump, land, surprise, pet. Idle breathing, blinking and random micro-motions.

## Behaviour (`Brain`, `Seqs`)
- 56 states, 41 sequences (28 autonomous incl. rare events + 13 reactions). Context-weighted choice: hour, scene weather, battery, charging, position, recent states, recent touches, sleep.

## Input
- Tap: head / belly / body / 3+ taps / sleeping / empty room. Slow stroke over the head = petting. Eyes then head follow the finger.

## Room (`Room`)
- Window, curtains, clock, shelf + plant, floor lamp, cushion, food + water bowls, pet bed, ball, charging pad.
- 10 scenes, time-of-day weighted, cross-faded over 6 s, drifting every 25-55 min. Rain, snow, stars, clouds, lamp flicker, light shaft.

## Kept
Clock look, charging run + rest, screen-on / unlock greeting, screen-edge exit / peek / re-entry, tap-away curiosity, ~30 fps cap, reduced rate when calm, stop when hidden.

## Added settings
Room scene picker (Auto or a fixed scene). `speed` pref now controls how lively the random behaviour is.
