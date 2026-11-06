# LOOT Android (Skyrim LE)

A small Android front-end for [LOOT](https://loot.github.io/) aimed at the
Skyrim Legendary Edition static Android port and the
[DroidModLoader](../DroidModLoader) fork.

The app asks for:

1. the game (Skyrim Legendary Edition),
2. the port root folder (the folder containing `Data`),
3. `Plugins.txt`, and
4. `loadorder.txt`.

It then provides two actions:

- **LOOT Sort** — sorts the plugin list with the real libloot engine and writes
  the result back to both text files.
- **LOOT Logs** — shows the last sort report, including LOOT messages,
  warnings, unreadable plugins, and the resulting order.

## Skyrim LE layout

DroidModLoader and the static port conventionally use:

```
<port>/
  Data/
    Skyrim.esm
    Update.esm
    ...
  save/AppData/Local/Skyrim/
    Plugins.txt
    loadorder.txt
```

The app auto-detects `save/AppData/Local/Skyrim` after the port folder is
selected. It writes plain plugin names, one per line, with no `*` prefix, to
match DML. `Plugins.txt` contains active plugins only; `loadorder.txt` contains
the complete order.

## Why all-files access is required

libloot is a native Rust library. It needs real filesystem paths, not Android
Storage Access Framework `content://` URIs, so the app requests
`MANAGE_EXTERNAL_STORAGE` (Android 11+) or legacy read/write permission. The
system folder picker is still used, and the app maps the chosen tree back to a
real path. If a picker uses a provider that cannot be mapped, the app asks for
the path directly.

## How it works

```
MainActivity (Kotlin)
  ├─ selects paths, reads/writes Plugins.txt and loadorder.txt
  ├─ downloads and caches the LOOT masterlist
  └─ LootBridge
       └─ Chaquopy → loot_android.py → libloot Python module (Rust/PyO3)
```

`loot_android.py` opens a `loot.Game` for Skyrim, loads the cached masterlist
(and `userlist.yaml` if present), loads plugin headers from `Data`, sorts all
installed plugins, and returns JSON.

## Build

### Requirements

- JDK 17
- Android SDK with platform 34
- Rust with the `aarch64-linux-android` target
- Python 3.13 on the build machine (Chaquopy uses it for build tooling)
- `cibuildwheel` (only needed to rebuild the libloot wheel)

### Rebuild the libloot Android wheel

Chaquopy cannot install the PyPI `libloot` package directly: it is a native
extension and has no Android wheel. Build one with:

```sh
./scripts/build-libloot-wheel.sh
```

The wheel is written to `app/src/main/wheels/` and is picked up by
`app/build.gradle` via `--find-links`.

### Build the APK

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
# Chaquopy requires a build-time Python matching the app's Python 3.13. If the
# host default python3 is a different minor version, point at a 3.13 interpreter:
export LOOT_BUILD_PYTHON=/path/to/python3.13
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Masterlist

The app downloads the Skyrim masterlist from the `v0.29` branch of
`loot/skyrim` and caches it for a week. The `master` branch is not used because
its current contents contain constructs that libloot 0.29 rejects. Keep the
download branch in step with the bundled libloot version.

## Current limitations

- Skyrim Legendary Edition only. The Python bridge has a small game-type map
  that can be extended for SE/AE later.
- The first sort needs an internet connection to download the masterlist.
- Plugins with unreadable headers are reported and left at the end of
  `loadorder.txt`.
- The app does not display or edit individual plugin metadata yet.

## UI style

The interface follows DroidModLoader's dark Material design: a near-black
background, raised surface cards, rounded buttons, and the Skyrim Nordic
accent palette. The launcher icon is the official LOOT treasure-chest logo
(from `loot/loot/resources/icons/loot.svg`) shipped as an adaptive icon.
