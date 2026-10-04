# Wall Breach

A native **mixed-reality defence game for Meta Quest 3**, built with the **Meta Spatial SDK**
(Kotlin / Android / Gradle). Portals crack open on your *real* walls (found with scene
understanding / MRUK), and glowing drones pour out at you. Blast them, deflect their shots with a
shield, survive escalating waves and a boss portal every 5th wave. Solo or **two-player local
co-op** on two headsets in the same room.

> Sister project to the WebXR game [Orb Smash: Duel](https://quest-orb-smash.vercel.app) — a different genre, native to Quest.

**Download:** [latest APK](https://github.com/thehumanworks/quest-wall-breach/releases/latest/download/wall-breach.apk) (also on the [Releases](https://github.com/thehumanworks/quest-wall-breach/releases) page; built by GitHub Actions from this source).

## How to play

| Action | Controllers | Hand tracking |
| --- | --- | --- |
| Fire blaster | Trigger (hold = auto-fire), either hand | Right-hand pinch |
| Shield | Hold grip on either hand; point the controller at the incoming shot | Left hand is always a shield |
| Calibrate co-op spot | A / X | Hold right pinch 1.5 s |
| Quit run / end co-op round | ☰ (left menu button) | — |

* **Drones**: yellow *scouts* kamikaze into you, green *gunners* orbit and shoot, purple *tanks* fire
  spreads, red *bosses* come out of a giant portal every 5th wave, spray bursts and summon scouts.
* **Shield** blocks enemy shots and **reflects them** — reflected bolts home in on drones.
* **Score**: kills × combo multiplier (×1 → ×8; every 4 kills within 3 s steps it up; getting hit resets it).
  You can also shoot enemy bolts out of the air.
* **Health**: 100. Between waves everyone heals +20. In co-op a downed player is revived at the end of the wave; the run ends only when both are down.
* High scores (solo and co-op) are saved on the headset.
* No room scan? It falls back to four virtual walls around you. Use **RESCAN ROOM** to run Space Setup.

### Two-player co-op (same room, same Wi-Fi, no server)

1. Both headsets on the **same Wi-Fi network** (not a guest network with client isolation).
2. Headset A: **HOST CO-OP**. Headset B: **JOIN CO-OP**. B finds A automatically (UDP broadcast + mDNS/NSD) and connects over TCP.
3. **Calibrate the shared spot on both headsets**: pick one physical spot (e.g. the front-left corner of a table), rest your **right controller** on it, pointing at the same wall, and press **A**. Do the same on the other headset at the same spot, pointing the same way. A yellow cross marks your calibration point. You'll see your partner's head/hands as a glowing avatar — if it sits on their real head, you're aligned.
4. Host presses **START CO-OP**. Same portals, same drones, shared score, separate health bars.

The host runs the authoritative simulation; the guest streams its head/hand/shield pose at 30 Hz and
shots (with client-side hit claims, validated by the host) and receives 20 Hz snapshots.

## Install on Quest 3 from a Mac (do this for each headset)

1. **Developer Mode** (once per headset): you need a Meta developer organisation (free) at
   <https://developers.meta.com/horizon/manage/organizations/create/>. Then, in the **Meta Horizon** phone app:
   *Devices → select the headset → Headset settings → Developer Mode → On*. Reboot the headset.
2. Install adb on the Mac: `brew install android-platform-tools`
3. Connect the headset with a USB-C cable, put it on and accept **Allow USB debugging** (tick *Always allow from this computer*).
4. Check it's visible: `adb devices` (with both headsets plugged in you'll see two serials).
5. Install:
   ```sh
   curl -LO https://github.com/thehumanworks/quest-wall-breach/releases/latest/download/wall-breach.apk
   adb install -r wall-breach.apk                     # one headset connected
   adb -s <serial> install -r wall-breach.apk         # pick a headset when both are connected
   ```
   Alternatively use **Meta Quest Developer Hub** (MQDH) for Mac: select the device → *Apps* → drag the APK in (or *Install APK*).
6. Launch: in the headset open the **App Library**, change the filter dropdown (top right, normally *All*) to **Unknown Sources**, and pick **Wall Breach**.
   Or from the Mac: `adb shell am start -n com.thehumanworks.wallbreach/.WallBreachActivity`
7. On first launch allow the **spatial data** permission (needed to read your room's walls).

Uninstall: `adb uninstall com.thehumanworks.wallbreach`. Logs: `adb logcat -s WallBreach WallBreachNsd`.

## Build from source

Requirements: JDK 17, Android SDK (platform 34, build-tools 35, NDK 27.0.12077973). Works headless on Linux/macOS.
For sound effects also `python3` + `numpy` + `ffmpeg` (`brew install ffmpeg && pip3 install numpy`).

```sh
sh bootstrap.sh                # fetches the Gradle wrapper (gradlew + jar) + synthesises the sound effects
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest    # simulation, protocol and loopback networking tests
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

The repository is plain text: the sound effects are synthesised by `tools/gen_sounds.py` (no audio
assets), the Gradle wrapper (`gradlew` + jar) is fetched from Gradle's repo and checksum-verified by `bootstrap.sh`, and a shared debug
keystore is stored base64-encoded and decoded by `app/build.gradle.kts`. The shared keystore means
every build has the same signature, so `adb install -r` can upgrade in place no matter where the APK
was built (your Mac, CI, or anywhere else).

## Code map

```
app/src/main/java/com/thehumanworks/wallbreach/
  WallBreachActivity.kt   Spatial SDK activity: passthrough, MRUK scene loading, panels, haptics
  GameController.kt       per-frame system: input, solo/host/client modes, events -> audio/haptics, UI
  RoomWalls.kt            MRUK wall anchors -> portal sites
  NsdHelper.kt            mDNS discovery (alongside the UDP beacon)
  render/                 procedural glowing meshes, effects, spatial audio
  ui/                     Compose in-world menu + HUD panels
  sim/                    engine-independent game simulation (pure Kotlin, unit-tested)
  net/                    wire protocol, TCP transport, UDP beacon, host/client sessions (pure Kotlin, unit-tested)
```

## Status

Built and unit-tested headless; **not yet run on a headset** (see the release notes / issues for
anything found during first play). Licensed MIT.
