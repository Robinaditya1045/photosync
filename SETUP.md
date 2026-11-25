# Home photo funnel: 4 Android devices → Pixel 1 → Google Photos

All devices use the same Google account. Only the **Pixel 1** uploads, because it gets
unlimited Original-quality storage. The other devices send their photos to the Pixel over
home Wi-Fi with **Syncthing**. This covers **existing photos as well as new ones**. Photos
on the other devices are never deleted automatically.

```
Phone A ─┐
Phone B ─┤  Syncthing (home Wi-Fi only, Send Only)    Pixel 1 (Receive Only)          Google Photos
Tablet C ┼──────────────────────────────────────▶  /sdcard/DCIM/Sync/<dev>-<folder>/ ──▶ (unlimited)
Phone D ─┘                                                   │
                                   Smart Storage frees space once photos are backed up
```

## Quick start (with the helper scripts)

Turn on USB debugging on each device, plug it in, then run:

```bash
./setup-device.sh --role pixel                    # once, on the Pixel
./setup-device.sh --role source                   # on each of the 4 other devices
```

The script does the following on each device:
- Installs Syncthing-Fork, grants it storage access and stops Android from killing it to
  save battery.
- **Pixel:** turns on Smart Storage, so photos that are already backed up get removed from
  the Pixel automatically when it runs low on space. This keeps the backlog moving.
- **Other devices:** lists which folders hold existing photos and how much will be sent,
  and adds a skip-list (`.stignore`) to the shared folders. It also installs the
  [sync switch & status widget](#sync-switch--status-on-the-source-phones) (skip it with
  `--no-widget`).

At the end it prints the few steps that have to be done by tapping on the device. If more
than one device is connected, add `--serial <id>` (see `adb devices`).

To send only *new* photos instead, use `--cutoff 2026-11`. Only photos dated from that
month onward are sent (see `gen-stignore.sh`).

## Manual steps

### All 5 devices
1. Install **Syncthing-Fork** (F-Droid / Play Store, or let the script do it).
2. Syncthing → Settings → **Run conditions** → Wi-Fi only → restrict to your **home SSID**.
3. Exempt Syncthing from battery optimisation (the script does this).

### Each source device (×4)
1. Add the Pixel as a remote device: scan the QR code from *Pixel → Syncthing → Show device ID*.
2. Add folders `DCIM/Camera` and `Pictures/Screenshots`. Also add any other folder the
   script listed that holds photos you want (e.g. `DCIM/Screenshots` on Samsung and Xiaomi).
   - Folder type **Send Only**, shared with **the Pixel only**.
   - WhatsApp is not included for now. To include it later, share its folder the same way.
3. **Google Photos on this device → Settings → Backup → OFF.** If backup stays on, the
   device uploads directly and uses your account storage.

### Pixel 1
1. Accept every folder the source devices offer. In the accept screen, store it at
   `/storage/emulated/0/DCIM/Sync/<DeviceName>-<Folder>` and set **Folder Type** to
   **Receive Only** before saving.
2. A folder starts syncing as soon as it is accepted, so do this straight after accepting
   **each** one: open Syncthing menu → **Web GUI** → Actions → Advanced → Folders →
   *folder*, set the following and press **Save**:
   | Setting | Value | Why |
   |---|---|---|
   | Type | `receiveonly` | the Pixel never sends anything back |
   | Ignore Delete | **ON** | when you delete a photo on a source phone, the Pixel copy is kept until it is backed up |
   | Min Disk Free | `15 %` | Syncthing pauses before the Pixel is completely full |
   | Order | `newestFirst` | recent photos never wait behind the backlog |
3. Google Photos → Settings → Backup **ON**, quality **Original**. Under
   **Back up device folders**, turn on every `Sync/...` folder.
4. Settings → Storage → **Smart Storage ON** (the script sets this; check it shows ON).
5. Keep the Pixel on the charger and connected to home Wi-Fi.

## Sync switch & status on the source phones

Each source phone gets the **Photo Sync** app (built from `widget/`). It is a small
home-screen widget and needs nothing besides Syncthing-Fork:

```
╭──────────────────────────────────────────╮
│  ◜ 71% ◝   Sending to Pixel           ⋯  │
│  ◟     ◞   312 files · 1.1 GB left       │
│            ● Pixel connected · 14:02     │
│  ┌────────────┬────────────┬───────────┐ │
│  │▌  Auto    ▐│     On     │    Off    │ │
│  └────────────┴────────────┴───────────┘ │
╰──────────────────────────────────────────╯
```

- **The ring** fills as photos reach the Pixel. Its colour shows the state: indigo while
  sending, green ✓ when done, amber when the Pixel is offline, grey when paused, red when
  something needs fixing.
- **Tap the status** to refresh it. While photos are being sent it refreshes every
  minute; otherwise every 30 minutes.
- **Make the widget taller** to see one line per folder (Camera, Screenshots…).
- **⋯** opens the setup screen.

| Switch | What it does |
|---|---|
| **Auto** | Normal mode: syncs only on home Wi-Fi |
| **On** | Syncs right now, even away from home. **Can use mobile data** when you're out. |
| **Off** | Stops syncing until you choose Auto or On |

| Status | Meaning |
|---|---|
| ✓ All photos on Pixel | The Pixel has every file. Google Photos on the Pixel uploads from there. |
| Sending to Pixel · N files left | Transfer in progress |
| Waiting for Pixel | Files are left, but the Pixel is off, asleep or not on Wi-Fi |
| Paused until home Wi-Fi / Sync is off | Syncthing isn't running. The last known numbers stay visible. |
| Pixel isn't taking every folder | A folder is paused on the Pixel, or wasn't accepted there |

**Deleted photos:** after you delete photos on the phone, the done state reads
*"N deleted here, kept on Pixel"*. This is the Ignore Delete safety setting at work, and it
still counts as done. Syncthing's own notification would show "Syncing 95%" forever in this
case, which is why the widget calculates the status itself.

**Setup.** `setup-device.sh` installs the app and opens it. The app's setup screen has
three steps:
1. **Allow switching:** Syncthing → Settings → **Behaviour** → turn on **Service Control
   by Broadcast**.
2. **Connect:** Syncthing → Settings → **Syncthing Options** → tap **Syncthing API Key**
   (this copies it). Go back to Photo Sync; the key is filled in from the clipboard. Tap
   **Save & test**.
3. **Add to home screen.** Or long-press the home screen → Widgets → Photo Sync.

**Building the app:** `widget/build.sh` writes `apk/photosync-widget.apk`. It needs
`curl`, `zip`, `unzip` and a JDK 17 on the PC.
`setup-device.sh` runs it automatically if the APK is missing. The first build downloads
the Android SDK tools into `~/Android/Sdk` and asks you to accept Google's licences. Keep
`widget/photosync.keystore`: updates must be signed with the same key.

Notes:
- Syncthing's own **"Force run"** quick-settings tile switches the same setting
  (*Following run conditions → Forced to run → Forced to stop*). The script adds it too.
  The widget's switch only knows about choices made on the widget.
- `phone/` holds the older Termux version of the switch. It is no longer installed.

## Existing photos (the backlog)

When a source device is first connected, Syncthing sends its whole camera roll. The
Pixel's storage is far smaller than four phones' worth of photos, so the backlog goes
through in waves:

1. Syncthing fills the Pixel, newest photos first.
2. Google Photos on the Pixel uploads them.
   - **Photos that are already in Google Photos are not stored twice.** Exact copies are
     recognised and simply marked as backed up. Syncthing copies files byte-for-byte, so
     anything you moved to the Pixel by hand before is matched. Google Photos may still
     spend some time checking each one.
3. When the Pixel drops below about 25 % free space, **Smart Storage** removes local
   copies that are already backed up. Syncthing sees them as "local changes" and does
   **not** download them again.
4. If the Pixel gets down to 15 % free, Syncthing pauses and continues by itself once
   space is freed.

**If it stays paused** (the Syncthing folder shows "insufficient space" and Google Photos
says everything is backed up), open Google Photos → **Free up space** once. Smart Storage
only removes photos older than 30 days. Google doesn't say whether it counts from when
the photo was taken or when it arrived on the Pixel. If it is the arrival date, the
automatic clean-up won't help with a fresh backlog.

**Progress:** the Pixel's Syncthing app shows each folder as *Syncing NN %*. Google
Photos shows *Backing up · N left*. A large backlog takes days. That's expected.

**Duplicates you might still see.** These cost no storage, because Pixel uploads are
free, but they do show up in the library:
- Photos edited or re-saved by a gallery app after they were uploaded no longer match exactly.
- Photos a source device once uploaded itself in *Storage saver* quality may be uploaded
  again at Original quality.

## Routine

- **Pixel storage:** normally automatic (Smart Storage). If sync stalls, run Google Photos →
  *Free up space*. **Never press "Revert local changes"** in Syncthing on the Pixel. That
  would download every freed photo again.
- **Source devices:** delete photos by hand once they appear in Google Photos.
- **Account storage:** check now and then that Google One storage isn't growing. The Pixel 1
  perk is Google's policy and could change.

## Check that it works

1. Take a photo on a source phone. Within about 1 minute it appears in `DCIM/Sync/...` on the Pixel.
2. Google Photos shows the cloud icon on it. At photos.google.com it is at full resolution.
3. Account storage has not increased.
4. Delete the test photo on the source phone. It must **still** be on the Pixel and in the cloud.
5. Run *Free up space* on the Pixel. Syncthing doesn't download the photo again.
6. **Backlog:** an old photo you know is already in Google Photos still appears only once
   in the library after it has gone through. Account storage is still unchanged after the
   first few thousand items.
