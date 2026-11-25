# Photo Sync redesign plan

Status: **proposed on 2026-11-25, not started.** Nothing in the code has changed yet.
Design mockups (widgets and app screens): open `design/index.html`; renders are in
`design/screenshots/`. The iOS companion plan is `PLAN-IOS.md`.

## Goal

Make the photo funnel usable without knowing what Syncthing is:

1. Hide the plumbing behind a few plain ideas (a hub, phones, albums).
2. Replace the single status widget with a family of bolder widgets.
3. Become one standalone app, so Syncthing-Fork is no longer needed.

## What is awkward today

- Each source phone needs two apps and three manual steps: turn on "Service Control by
  Broadcast", copy the Syncthing API key, paste it into Photo Sync.
- Pairing, folder types and the four Advanced settings per folder on the Pixel are done by
  hand in Syncthing and its Web GUI.
- First setup needs a PC, a USB cable and `setup-device.sh`.
- Syncthing keeps two folders identical. This job is "deliver each photo once", so we
  work against it: `ignoreDelete`, the 95 % status workaround in `Status.java`, and the
  "never press Revert local changes" rule all come from that mismatch.

## The model the user sees

| Idea | Meaning |
|---|---|
| **Hub** | The Pixel. Receives photos and lets Google Photos upload them. |
| **Phone** | Any other device. Sends its photos to the hub. Never deletes anything. |
| **Albums** | The photo folders on a phone, each with an on/off switch. Camera and Screenshots start on; WhatsApp starts off. |
| **Auto / Paused** | Auto sends whenever the phone and the hub are on the same Wi-Fi. Paused sends nothing. |

What each Syncthing concept becomes:

| Today | In the new app |
|---|---|
| Device ID, QR code, "Add device" | The phone finds the hub on Wi-Fi; you type a 6-digit code shown on the hub |
| Folders, Send Only / Receive Only | Album switches; the role decides the direction |
| `ignoreDelete`, `minDiskFree`, pull order | Built in, not shown |
| `.stignore`, `--cutoff` | One switch: "Older photos too" |
| API key, Service Control by Broadcast | Gone (standalone engine) |
| Run conditions, home SSID | Auto only talks to the hub on the local network, so it is home-only by construction |
| Auto / On / Off | Auto / Paused. "On" (which could use mobile data) is dropped |
| `setup-device.sh` over USB | Install one APK; the app asks for its own permissions |

Still manual, because Android gives no way to automate them:
- Google Photos settings: backup on at Original quality on the hub, backup off on phones.
- "Free up space" on the hub. The app shows when it is needed and opens Google Photos.
- Xiaomi "Autostart" and battery settings. The app explains them on first run.

## Design

Look: near-black rounded tiles, heavy numerals, one loud colour tile for the "all safe"
state. Type: Bricolage Grotesque (numerals, titles) and Inter (labels); both are
open-licensed and get bundled in the APK. Colours: ground `#16171B`, raised `#24262C`,
progress `#8C9BFF`, safe `#3DDC97`, attention `#FF9A4D` / `#FFC24D`.

Widgets on a phone:
- **Week (4×2):** last 7 days by date taken. Each day is a tick (all sent), a ring
  (partly sent), or a dash (no photos). Counters for photos and videos sent this week.
- **Progress (2×2):** ring with percent, photo and video counters, items left.
- **Count (2×2):** the number left to send, large.
- **All safe (2×2):** the loud tile, shown when nothing is left.
- **Other states:** waiting for home, can't find the hub, paused, hub is full.

Widget on the hub: **Hub (4×2)** with one tile per phone and free space.

App screens: choose a role → connect to the hub (code) → choose albums → home. The hub
has its own home screen: free space, the list of phones, a backup checklist.

Implementation note: the widgets are drawn to a Bitmap with `Canvas` (as the ring is
today) so the custom font and shapes work on every launcher. One small widget provider
switches between Progress, Count, All safe and the other states by itself.

## Architecture

```
core/      plain Java, no Android, runs on the PC for tests
  Snapshot       what any screen or widget shows (was Report): state, items left,
                 totals, the 7-day history, per-album and per-phone rows
  SyncEngine     the only thing the UI talks to: snapshot(), setMode(),
                 albums(), setAlbum(), pairing
  Ledger         record of what was sent / received
  proto/         HubServer, SenderClient (the Direct engine's wire protocol)
engine/
  SyncthingForkEngine   today's Api + Status + broadcast switching, behind SyncEngine
  DirectEngine          the standalone engine
platform/  Android glue: MediaStore index, hub service, send job, Wi-Fi discovery, storage
ui/        Welcome, Pair, Albums, Home, HubHome, Settings
widget/    WidgetRenderer (Canvas), Week / Small / Hub providers
```

The `SyncEngine` boundary is what makes the phases independent: the new widgets ship on
the Syncthing engine first, and the engine is swapped later without touching the UI.
The build stays Gradle-free (`widget/build.sh`), package `home.photosync`, same keystore,
so existing installs upgrade in place and keep their settings.

## Standalone: which engine

Both options give one app. Recommended: **Direct**.

| | Direct (own engine) | Embedded Syncthing |
|---|---|---|
| How | Phone uploads files to a small server in the hub app | Ship Syncthing's binary inside our APK and drive it |
| Fit | "Deliver once", with a ledger | "Keep folders identical", bent to fit, as today |
| After Free up space or a reinstall | Ledger remembers; nothing is re-sent | A fresh database re-downloads everything already freed |
| Status | Exact, per file and per day | Derived from REST, keeps the 95 % workaround |
| Storage permission | Photos and videos only | All files |
| APK size | Under 1 MB | About 27 MB more (13 MB per CPU type, measured from the Fork's APK) |
| Transfer code | Ours, new, must be proven | Mature |
| Testable on the PC | Yes, the core is plain Java | No |

The cost of Direct is that the transfer code is new. Two things limit the risk: phones
never delete, so the worst failure is "not sent yet" and is visible in the app; and every
file is checked with SHA-256 on the hub before it counts as sent. If Direct fails its
trial (phase 3), Embedded Syncthing can still be slotted in behind `SyncEngine`.

### Direct engine in brief

- **Hub:** a foreground service runs a small HTTPS server and announces itself on the
  local network (Android NSD). It writes each file to `DCIM/PhotoSync/<Phone>/` through
  MediaStore, so Google Photos needs one "back up this folder" switch per phone instead
  of one per folder. It refuses uploads below 15 % free space and tells the phone why.
- **Phone:** lists photos and videos through MediaStore, asks the hub which ones it
  lacks, and uploads those newest first, resuming partial files. A job wakes it when new
  media appears and Wi-Fi is available; a long backlog runs as a foreground service with
  a progress notification.
- **Ledger:** the hub records every file it has received (phone, path, size, modified
  time, hash). The record outlives the local copy, so freed photos are never sent again.
- **Pairing:** the hub shows a 6-digit code for two minutes; the phone sends it once and
  gets a long-lived key. Traffic is encrypted, with the hub's certificate pinned at
  pairing.

### If iPhones are to be supported

iOS only uploads in the background through a system service that the app cannot steer
request by request. `PLAN-IOS.md` section 7 lists what that asks of the protocol and the
hub. The main points: one plain request per file with the file as the whole body, the
standard resumable-upload headers, a long-lived header token instead of a login exchange,
results given by status code alone, and a ledger keyed by an opaque id chosen by the phone.
Certificate pinning does not work for those system-run uploads, so encryption needs its
own decision there. None of this is adopted yet. It has to be settled before phase 2,
because phase 2 fixes the protocol.

### Moving over from the Syncthing setup

Per phone, while Syncthing-Fork is still installed:
1. The new app is an upgrade of the current one, so it still has the Syncthing API key.
2. It asks Syncthing what the Pixel still needs (`/rest/db/completion`,
   `/rest/db/remoteneed`) and marks everything else in the shared folders as already sent.
3. The user pauses that phone's folders in Syncthing. From then on Direct carries new photos.
4. After a week without problems, uninstall Syncthing-Fork from that phone.

Files already on the Pixel under `DCIM/Sync/` stay where they are.

## Phases

1. **New look on today's engine** (medium). `SyncEngine`, `Snapshot`,
   `SyncthingForkEngine`, the Canvas renderer, the Week and small widgets, Auto / Paused.
   The 7-day history comes from `/rest/db/browse` and `/rest/db/remoteneed`.
   Check: screenshots from the Xiaomi over adb in each state.
2. **Direct engine on the PC** (large). `core/proto` and `Ledger` with a test script that
   runs a hub and a phone as two JVM processes: large backlog, kill and resume, corrupted
   file, hub full, name clashes. No Android code yet.
3. **Standalone app** (large). Roles, pairing, albums, hub service, send job, both home
   screens, the Hub widget, a quick-settings tile. Trial on the Xiaomi and the Pixel using
   the migration steps above.
4. **Cut over and clean up** (small). Migrate the other phones, remove Syncthing-Fork,
   reduce `setup-device.sh` to an optional installer, rewrite `SETUP.md`, delete `phone/`
   and the Termux APKs, rename `widget/` to `app/`.

Phase 1 is useful by itself and does not depend on the engine decision.

## Risks to test early

- **Background sending on Xiaomi (MIUI/HyperOS).** It kills background apps. Needs
  Autostart and no battery restriction, as Syncthing-Fork did. Test in phase 3 before
  building the rest of the UI.
- **Finding the hub.** Some routers block discovery between Wi-Fi clients. Fallback: the
  hub's last known address, then a scan of the local subnet for its port.
- **Encryption without extra libraries.** Creating the hub's certificate on the device
  needs a short proof of concept at the start of phase 2.
- **Hub service lifetime on the Pixel 1 (Android 10).** Must survive days on the charger.
- **Android 14 partial photo access.** If the user grants "selected photos" only, the app
  must ask for full access.
- **Widget bitmaps.** Launchers cap bitmap size and may scale them; check on the Xiaomi
  and Samsung launchers.

## Open decisions

1. Engine for the standalone app: Direct (recommended) or Embedded Syncthing.
2. The look on the canvas: approve, or say what to change.
3. One hub folder per phone (`DCIM/PhotoSync/<Phone>/`) instead of one per album.
4. Dropping "On" (sending away from home over mobile data).
5. Is iOS in scope? If yes, take `PLAN-IOS.md` section 7 into this plan before phase 2,
   and run its background-upload test (phase I1) first.
