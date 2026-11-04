# Open GAN for the M5Stack Cardputer ADV

A pocket version of Open GAN: drives the **GAN Robot** scrambler and times solves on
**GAN smart cubes** (Gen2, Gen3, Gen4), all on the Cardputer's own screen and keyboard.
No phone needed. No ads, no tracking, no Wi-Fi: it only uses Bluetooth LE to talk to
your robot and cube.

> Status: first version, not yet tested on real hardware.

## Features

- **One-key Connect** (`C`): finds the robot, has it make a quick turn and back to wake the
  smart cube inside it, then connects the cube. Remembers both for next time.
- **Robot scramble** (`R`): random robot-safe scrambles (no U turns). With a smart cube in the
  robot it first checks how the cube is placed, checks every turn against the cube and
  fixes missed turns.
- **Robot solve** (`S`): the robot undoes every move since the cube was last solved.
- **Smart cube timer**: tracks your scramble move by move (applied moves dim, next move yellow),
  arms itself when the cube is scrambled, starts on the first turn, stops when solved. Times
  come from the cube's own clock.
- Space bar timer when no smart cube is connected.
- ao5, ao12, ao100, mean, best, +2 / DNF, stored on the Cardputer (up to 1000 solves).
- Beeps when the cube is ready and when you finish (`M` mutes), and a little confetti.

## Keys

| Key | Action | Key | Action |
| --- | --- | --- | --- |
| `C` | connect robot + cube | `K` | disconnect |
| `N` | new scramble | `T` | type your own scramble |
| `R` | robot scramble | `S` | robot solve |
| `O` | check cube placement in robot | `X` | "my cube is solved" (resync) |
| Space / Enter | arm smart timer / tap timer | `L` | solves list |
| `2` / `D` | +2 / DNF on the last solve | `H` | help |

In the solves list: `;` and `.` move up and down, `2` / `D` toggle penalties, `Del` deletes,
`` ` `` goes back.

## Build and flash

Install [PlatformIO](https://platformio.org/install/cli), plug the Cardputer in over USB-C, then:

```sh
cd cardputer
pio run -t upload        # build and flash
pio device monitor       # optional: serial log
```

If the upload can't find the board, hold the **G0** button while plugging in USB to enter
download mode, then run the upload again and press reset afterwards.

## Code layout

| Path | What |
| --- | --- |
| `src/core/` | Plain C++17, no Arduino: cube model, AES-128, GAN Gen2/3/4 drivers with missed-move recovery, robot encoder, placement detection, robot solver, scrambler, smart timer, stats. A port of the Kotlin `core/` module. |
| `src/ble.*` | NimBLE scanning, cube client (decrypt + driver), robot client. |
| `src/app.*` | App logic (same flow as the Android `MainViewModel`), background worker for robot and connect jobs, solve storage in NVS. |
| `src/main.cpp` | Screen (240x135) and keyboard UI. |
| `test/host/` | Unit tests for `src/core` that run on a PC: `make -C cardputer/test/host`. |

## Supporting the project

Open GAN will always be free and ad-free. If it helps your cubing, please consider a
donation (see the main [README](../README.md)).

GPL-3.0-or-later. Credits as in [NOTICE](../NOTICE). Not affiliated with or endorsed by GAN.
