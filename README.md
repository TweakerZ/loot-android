# LOOT Android (Skyrim LE)

Sorts Skyrim Legendary Edition plugin load orders with the real LOOT engine.
Companion app for [Skyrim Mod Loader](https://github.com/TweakerZ/Skyrim-Mod-Loader).

## What it does

- Sorts plugins with libloot 0.29.7, the engine behind desktop LOOT.
- Downloads the Skyrim masterlist once a week and caches it.
- Writes `Plugins.txt` (active plugins) and `loadorder.txt` (full order) in
  place, with plain plugin names and no `*` prefix.
- Finds `save/AppData/Local/Skyrim` inside the selected port folder.
- Reports the sort: LOOT messages, warnings, unreadable plugins and the final
  order.

## Requirements

- A Skyrim Legendary Edition static Android port.
- An arm64-v8a device running Android 7.0 (API 24) or newer.
- All-files access. libloot reads and writes real paths, not SAF URIs.
- Network access on the first sort, to fetch the masterlist.

## Usage

1. Grant all-files access.
2. Open the gear and pick the port folder, the one holding `Data`. Both text
   files are detected under `save/AppData/Local/Skyrim`, or picked by hand.
3. Tap LOOT Sort. The files are rewritten in place and `.lootbak` copies are
   kept.
4. Tap LOOT Logs for the last report.

## Building

You need JDK 17, Android SDK platform 34, Python 3.13, and a checkout of
`skyrim-suite-ui` in `../skyrim-suite-ui`. `app/build.gradle` adds that
directory to the Kotlin source set, so the build fails without it.

```sh
source ~/.android-builds/env.sh   # or set JAVA_HOME and ANDROID_HOME yourself
export LOOT_BUILD_PYTHON=/path/to/python3.13
./gradlew assembleRelease
```

Chaquopy installs the prebuilt libloot wheel from `app/src/main/wheels`.
`scripts/build-libloot-wheel.sh` rebuilds it with cibuildwheel, Rust and the
`aarch64-linux-android` target.

## Layout

```
app/src/main/kotlin/com/lootandroid   UI, sorting flow, storage mapping
app/src/main/python/loot_android.py   libloot wrapper called through Chaquopy
app/src/main/wheels                   libloot Android wheel for Python 3.13
app/src/main/res                      theme, strings, launcher icon
scripts/build-libloot-wheel.sh        wheel rebuild script
```

## Credits

LOOT and libloot are by the [LOOT team](https://github.com/loot).
Skyrim Mod Loader is a fork of Droid Mod Loader by CyberShonk.

Yes, AI was used in building this.