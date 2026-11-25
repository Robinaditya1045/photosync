# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Home automation that funnels photos from 4 Android "source" devices to a **Pixel 1** (unlimited Original-quality Google Photos) over home Wi-Fi using **Syncthing-Fork**. Only the Pixel uploads to Google Photos. Source devices keep their copies; nothing on them is ever deleted automatically. `SETUP.md` is the user-facing guide and the source of truth for the manual steps on each device.

There is no test suite or linter. Work is checked by running against real phones over `adb`.

Branches: `stable/v1` (tag `v1.0.0`) is the released Syncthing-based version and takes fixes only. `main` is where the redesign happens. `widget/photosync.keystore` and `apk/` are deliberately not in git; the built Photo Sync APK is attached to GitHub releases.

## Planned redesign

`PLAN.md` (Android) and `PLAN-IOS.md` (iOS) describe a planned move to one standalone app. They are proposals: check their "Open decisions" sections with the user before implementing. `design/` holds the mockups as standalone HTML (open `design/index.html`); `design/README.md` has the colour tokens and the command that regenerates `design/screenshots/`. Everything below describes v1, the code that exists today.

## Commands

```bash
widget/build.sh                                   # build Photo Sync widget -> apk/photosync-widget.apk
./setup-device.sh --role pixel                    # provision the Pixel (one adb device connected)
./setup-device.sh --role source                   # provision a source phone (installs Syncthing + Photo Sync)
./setup-device.sh --role source --serial <id> --no-widget --cutoff 2026-11
./gen-stignore.sh all|YYYY-MM                     # print the .stignore that setup-device.sh pushes
adb devices -l                                    # find serials; "unauthorized" = accept the USB-debugging prompt
```

`setup-device.sh` can be run more than once on the same phone. It skips apps that are already installed. Reinstalling Photo Sync brings up the phone's install prompt again, so pass `--no-widget` when you only need the Syncthing or skip-list steps.

## Architecture

**Pipeline:** each source folder is **Send Only** and shared only with the Pixel. On the Pixel it is **Receive Only**, stored at `/storage/emulated/0/DCIM/Sync/<Device>-<Folder>`, with `ignoreDelete` on, `minDiskFree` 15% and `newestFirst` order. Google Photos on the Pixel backs up those folders and skips exact duplicates. Google Photos backup stays **off** on the source devices.

**Freeing space on the Pixel:** Smart Storage is turned on by `settings put secure automatic_storage_manager_*`, but it only deletes photos when storage is low and they are older than the retain-days setting. In practice the user taps Google Photos → "Free up space". Because `ignoreDelete` is on, Syncthing never downloads the freed photos again. Never press "Revert local changes" in Syncthing on the Pixel.

**`setup-device.sh`** does everything that adb can do from a PC:
- Downloads the latest Syncthing-Fork APK from GitHub into `apk/` (repo `researchxxl/syncthing-android`, package `com.github.catfriend1.syncthingfork`, Java namespace `com.nutomic.syncthingandroid`).
- Grants permissions and adds a battery-optimisation exemption.
- On source phones: lists the photo folders, pushes `.stignore`, installs `apk/photosync-widget.apk` and adds the Syncthing "Force" quick-settings tile.

The script runs under `set -euo pipefail`. Steps that a phone may refuse over USB (for example `pm grant` on MIUI) must not abort it, so capture `rc` explicitly the way `install_github_apk` does. Xiaomi/MIUI phones need "Install via USB" and "USB debugging (Security settings)" turned on before installs work.

**`widget/`**: a native Android app (`home.photosync`) built **without Gradle**. `build.sh` calls aapt2, javac, d8, zipalign and apksigner directly, using SDK 34 and minSdk 24, and installs the SDK into `~/Android/Sdk` on first run.
- Java is compiled at `-source 8`. Lambdas need `core-lambda-stubs.jar` on the bootclasspath (already set up in `build.sh`).
- Keep `widget/photosync.keystore`. Updates must be signed with the same key or `adb install -r` fails.
- The version code is minutes since the epoch, so every build upgrades the installed app.

How the widget code is split:
- `Api`, `Status` and `Report` are **plain Java with no Android APIs**, so they can be tested on a PC against a mock Syncthing server.
  - `Api` talks to Syncthing's REST API at `https://127.0.0.1:8384`. It accepts the self-signed certificate and falls back to HTTP.
  - `Status` computes what is left to send from `/rest/db/completion` for the Pixel. It uses `needItems` and `needBytes` and deliberately **ignores `needDeletes`**, because with `ignoreDelete` on, Syncthing reports 95% forever.
- `Store` keeps the API key, the chosen mode and the cached last report. It switches Syncthing by broadcasting `<pkg>.action.START/STOP/FOLLOW` to `com.nutomic.syncthingandroid.receiver.AppConfigReceiver`. This requires Syncthing → Settings → Behaviour → "Service Control by Broadcast".
- `SyncWidget` draws the widget with RemoteViews: a rounded card, a progress ring drawn to a Bitmap and coloured by `Report.Kind`, rows per folder when the widget is tall enough, and an Auto / On / Off segmented switch. It refreshes every minute while sending; otherwise it uses the 30-minute system update.
- `SetupActivity` is a one-screen setup: allow broadcast control, paste the API key (read from the clipboard on focus), and add the widget.
- Night theme colours are in `res/values-night`, and Android 12+ corner radii are in `res/values-v31`.

**Legacy:** `phone/` (the Termux `photosync` script and installer) and the `termux-*` APKs in `apk/` belong to the earlier Termux:Widget version. The native widget has replaced them; they are kept only for reference.

## User decisions to respect

- WhatsApp folders are excluded for now.
- Photos that are already backed up must not cause duplicates or use up storage.
- The backlog of existing photos is synced as well as new ones. `--cutoff all` is the default, and `--cutoff YYYY-MM` limits syncing to photos from that month onwards.
