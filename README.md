# Open GAN

A free, open-source Android app for the **GAN Robot** scrambler and **GAN smart cubes**.
No ads, no tracking, no accounts, no internet permission. Funded by donations.

> Status: early scaffold (v0.1.0). The protocol core is unit-tested; the Android app
> has not been tested on real hardware yet. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Features (MVP)

- **GAN Robot**: connect without needing a smart cube, run random scrambles (robot-safe,
  no U turns) or type your own. Works fully offline.
- **Robot solve**: the robot undoes every move since the cube was last solved (from the smart
  cube's tracking, or the robot's own scrambles). U turns are rewritten without U.
- **Smart cube timer**: GAN Gen2, Gen3 and Gen4 protocols (GAN356 i3 / i Carry / i Carry 2,
  GAN12 ui / Maglev, GAN14 ui, GAN Mini ui, Monster Go 3Ai, MoYu AI 2023).
  The timer tracks your scramble move by move, arms itself when the cube is scrambled,
  starts on the first turn and stops when solved. Times come from the cube's own clock.
- **Robot + cube together**: put the smart cube in the robot and tap *Robot scramble*.
  The app sees the robot's turns, arms the timer, and you just pick up the cube and solve.
- Live 2D cube view, Ao5 / Ao12 / mean, +2 / DNF, solves stored locally in a plain text file.
- Manual tap timer when no smart cube is connected.

## Building

Requires JDK 17+ and the Android SDK (API 35).

```sh
./gradlew :core:test          # protocol unit tests (pure JVM)
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

No Android SDK? `OPENGAN_CORE_ONLY=1 ./gradlew :core:test` builds and tests just the core.
CI builds a debug APK on every push (see the Actions tab).

## Project layout

| Module | What |
| --- | --- |
| `core/` | Pure Kotlin, no Android: cube model, GAN cube protocol drivers + encryption, robot encoder, scrambler, smart timer state machine, stats. |
| `app/` | Android: BLE (`ble/`), Jetpack Compose UI (`ui/`), ViewModel, local solve storage. |

## Supporting the project

Open GAN will always be free and ad-free. If it helps your cubing, please consider donating
(links in the app's Support tab). Donations fund development time and test hardware
(new GAN cube models, robots).

## Credits and license

GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

- Smart cube protocol ported from [gan-web-bluetooth](https://github.com/afedotov/gan-web-bluetooth) by Andy Fedotov (MIT).
- Robot protocol documented by [rsimp/gan-scrambler](https://github.com/rsimp/gan-scrambler) (independent implementation; that repo has no license).
- Cube-clock solve timing pioneered by Chen Shuang in [csTimer](https://github.com/cs0x7f/cstimer).

Not affiliated with or endorsed by GAN.
