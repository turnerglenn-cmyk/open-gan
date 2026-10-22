# Architecture and roadmap

## Research summary

| Project | License | What we use |
| --- | --- | --- |
| [afedotov/gan-web-bluetooth](https://github.com/afedotov/gan-web-bluetooth) | MIT | Ported to Kotlin: Gen2/3/4 BLE UUIDs, AES keys, encryption scheme, protocol drivers (incl. missed-move recovery), timestamp linear fit. Attribution in NOTICE. |
| [afedotov/gan-cube-sample](https://github.com/afedotov/gan-cube-sample) | MIT | Reference for UX (3D cube, gyro). Not ported yet. |
| [shanedirksen/gan-cube-windows-app](https://github.com/shanedirksen/gan-cube-windows-app) | MIT | Tauri wrapper of the same library; nothing extra needed. |
| [rsimp/gan-scrambler](https://github.com/rsimp/gan-scrambler) | **None** (all rights reserved) | Only protocol facts (UUIDs FFF0/FFF2/FFF3, model "GAN ROBOTCUBE", move nibble encoding, 16-byte writes, busy polling). Code not copied. Its 5-face solvers are not reusable. |
| [cs0x7f/cstimer](https://github.com/cs0x7f/cstimer) | GPL-3.0 | Idea only (time from cube clock). Compatible with our GPL-3.0 if we later port pieces. |

### GAN Robot protocol

- Device Information `0x180A` / `0x2A24` model number must read `GAN ROBOTCUBE`.
- Service `0xFFF0`. Write scrambles to `0xFFF3`; read status from `0xFFF2` (non-zero = busy).
- Moves R R2 R' F F2 F' D D2 D' L L2 L' B B2 B' = codes 0..14. Two moves per byte
  (high nibble first), `0xF` pads an odd count, max 16 bytes per write; wait for
  busy -> idle between writes.
- No U turns (the robot grips U). Doubles are sent as two quarter turns because the
  robot sometimes fails on doubles.

## Architecture

```
app (Android)                                  core (pure Kotlin, JVM-tested)
-------------                                  ------------------------------
ui/ Compose screens  ──> MainViewModel ──────> SmartTimer, Scrambler, Stats
                              │
ble/ DeviceManager (scan, connect, detect)
     GanCubeClient  ── decrypt ──────────────> GanEncrypter, GanGen{2,3,4}Driver ─> GanCubeEvent
     GanRobotClient ── encode ───────────────> GanRobotProtocol
     GattConnection (serialized coroutine GATT ops)
SolveStore (TSV file in app storage) ────────> Solve
```

Key choices:

- **Kotlin + Jetpack Compose + Android BLE**, minSdk 26, no third-party dependencies
  beyond AndroidX/coroutines. Keeps F-Droid inclusion easy.
- **No INTERNET permission.** The strongest privacy promise we can make, and verifiable by anyone.
- **Protocol code is Android-free** so it is fast to test and could later be shared with
  a Kotlin Multiplatform iOS app (native iOS apps *can* use Bluetooth, unlike iOS browsers).
- Android gives the real device MAC, so no manual MAC entry like Web Bluetooth needs.
- One device auto-detects as cube or robot after connecting (by GATT services / model number).

## MVP scope (v0.1)

- [x] Scan, connect, auto-detect cube vs robot
- [x] Robot: random robot-safe scramble, manual scramble, progress
- [x] Smart cube: Gen2/3/4, battery, hardware info, live state net, "cube is solved" reset
- [x] Smart timer: scramble tracking, auto-arm, start on first turn, stop on solve, cube-clock timing
- [x] Robot + smart cube combined flow
- [x] Tap timer fallback, Ao5/Ao12/mean/best, +2/DNF, local storage
- [ ] Test on real hardware (needs a GAN Robot and at least one cube per protocol generation)

## Next (v0.2+)

1. **Two-phase solver (U-free)**: solve any state, not only tracked history, in ~20-25 moves.
   Today's robot solve undoes history and rewrites U as (R L' F2 B2 R L') D (R L' F2 B2 R L').
2. **Random-state scrambles**: port a two-phase solver (cs0x7f/min2phase) and generate
   U-free solutions so robot scrambles are WCA-quality.
2. Robot CFOP-stage scrambles (cross / F2L / OLL / PLL practice) like gan-scrambler.
3. Solve reconstruction with CFOP step splits (cross, F2L pairs, OLL, PLL) from move data.
4. 3D cube with gyro orientation.
5. GAN Smart Timer support (already in gan-web-bluetooth).
6. Export / import (csTimer-compatible), sessions, charts.
7. Releases: F-Droid + Google Play, GitHub Sponsors / Ko-fi / Liberapay.

## Open questions

- Final app name: "GAN" is a trademark; a neutral name avoids store takedown risk.
- Confirm the robot's advertised BLE name starts with "GAN" (scan filter assumes this).
