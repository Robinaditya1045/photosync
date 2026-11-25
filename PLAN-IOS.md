# Photo Sync on iPhone and iPad

Status: **proposed on 2026-11-25, not started.** Companion to `PLAN.md`. Section 7 lists
what this plan asks of the Android plan; `PLAN.md` stays as it is until those are accepted.

## 1. Can it work?

Yes, an iPhone or iPad can be a **phone** (a sender), with one big limit: iOS does not let
apps run when they like, so "Auto" cannot mean "send as soon as the hub is reachable".
Since iOS 26.1, PhotoKit (Apple's photo library framework) has an extension for backup apps
that lets the system upload new photos while our app is closed. The system picks the time,
and an Apple engineer's estimate is about **once a day, while the phone charges and is
idle**. No, an iPhone cannot be the **hub**, for two separate reasons. iOS suspends
background apps, so it cannot keep a server up for days. And the hub exists for the
Pixel 1's free Original-quality storage, which an iPhone does not have.

## 2. Recommendation

1. **Now, no code:** Möbius Sync on the iPhone, sending to the Pixel's existing Syncthing
   (section 8). This also shows how Google Photos treats iPhone files.
2. **Before `PLAN.md` phase 2 fixes the protocol:** a spike (throwaway test app) proving
   that the PhotoKit extension can upload to a server on the home network (phase I1).
3. **After `PLAN.md` phase 3's trial:** a native SwiftUI app for iOS 26.1+. It sends at once
   while open, finishes a backlog after "Send now", and leaves the rest to the extension.
4. **Distribution:** the paid Apple Developer Program plus TestFlight (Apple's app for test
   builds). CI builds on GitHub Actions' macOS machines from a text spec; no Mac day to day.

## 3. Background sending: the hard constraint

| Way to send | iOS | What it does | What the user gets |
|---|---|---|---|
| App open | any | Uploads in our own process; certificate pinning works | Everything goes at once |
| `BGContinuedProcessingTask` | 26.0 | A task started by a tap. It keeps running after the user leaves the app, with progress in a Live Activity (a system status card) | "Send now" finishes a backlog in the background. Swiping the app away cancels it |
| Background `URLSession` | 7 | A system process uploads files we hand it, even if iOS stops the app. It resumes by itself (iOS 17+) if the server speaks the IETF resumable-upload draft | Finishes started uploads. It cannot notice new photos |
| `BGAppRefreshTask` | 13 | Up to 30 s, at times the system picks | Queues a few uploads now and then |
| `BGProcessingTask` | 13 | Longer runs, "basically once per day ... while you're sleeping" (Apple DTS) | A nightly batch. Only for devices that can't get iOS 26.1 |
| **PhotoKit background upload extension** | **26.1** | The system starts our extension when there is upload work and conditions allow. We create one job per file; the system uploads it, fetching it from iCloud first | **New photos arrive without opening the app**, about daily on the charger; one developer saw about 1 minute on a test phone |
| Silent push from the hub | 7 | Needs Apple's push service (paid program); throttled above 3 an hour | Not worth it |

Facts behind the table (Apple docs and forums, 2025–2026, unless marked; DTS is Apple's
developer support):
- **The extension** is `PHBackgroundResourceUploadExtension` (iOS 26.1), deprecated in iOS 27
  for the async `PHBackgroundResourceUploadJobExtension`. It is an ExtensionKit (Apple's newer
  extension format) extension at `com.apple.photos.background-upload`. Info.plist (the app's
  settings file) must have `BackgroundUploadURLBase` "for network access validation". It
  needs **full** photo access and `setUploadJobExtensionEnabled(true)`. Apple names no
  entitlement (signed permission flag); that none is needed is not verified.
- **Jobs:** one request per file, with the file as the whole body: no chunking, no
  multipart. Unacknowledged jobs are capped (`jobLimit`). The extension sees only the status,
  the response headers and a cleaned-up URL error. Resume follows the IETF draft: `OPTIONS`
  answered with `Upload-Limit`, then a `104` response with `Location`. A Live Photo is two
  jobs, linked "in the upload URL or in the URL metadata" (Apple DTS).
- **The only photo-library trigger** that can start us while closed is this one. After a
  run, "the system enters monitoring mode for library changes". A normal change observer
  works only while the app runs. iOS 27 adds Settings → Developer → "Resource Upload Test
  Mode" to run it promptly, for builds run from Xcode only.
- **Known bug:** on iOS 26.4 it is never scheduled while iCloud Photos is on. Apple's
  engineers call that a bug; a fix in iOS 27 is not verified.
- **Home-network server:** one hobby project reports "HTTP to a LAN address is allowed" and
  "a self-signed certificate works only if the phone trusts it", with `BackgroundUploadURLBase`
  set to `https://github.com` as a mere "is the network up" probe. Not verified with Apple.
- **Background `URLSession`** uploads only from files, so photos are first copied out of the
  library. A LAN IP should work once the app has local network permission (not verified for
  these sessions). Pinning a self-signed certificate works, but each check wakes the app and
  Apple's Quinn says those wake-ups are rationed; he recommends a header token instead.

**Recommended:** app open + "Send now" + the extension. The nightly `BGProcessingTask` path is
built only if a family device cannot run iOS 26.1. "Auto" then means "send whenever iOS
gives us time and the hub answers". **What to promise the family:** photos usually reach the
hub overnight while the iPhone charges on home Wi-Fi; opening the app sends at once. Whether
the overnight path survives swiping the app away is not verified.

## 4. Photo access

- **Permission:** full or limited ("selected photos"). The extension needs full access, so
  the app asks again if it only got some photos.
- **Albums:** iOS has one library, not folders. The switches become: camera photos (on),
  Screenshots (on, a built-in smart album), albums made by apps such as WhatsApp (off).
- **iCloud "Optimize iPhone Storage":** originals may be only in iCloud. The extension fetches
  them itself (Apple engineer); the app uses `PHAssetResourceManager` with network access
  allowed. Each such photo is downloaded from iCloud first and needs space on the phone
  until it has been sent.
- **Formats:** send original bytes: HEIC/HEVC ("High Efficiency", the default) or JPEG/H.264
  ("Most Compatible"). Google lists `.heic`, `.heif` and `.mov`. Not verified on the Pixel 1:
  MediaStore on Android 10 taking `.heic`, and those files counting as free Original quality.
- **Live Photos** are a `.photo` plus a `.pairedVideo`. Google supports them "when you use the
  Google Photos app on your iPhone or iPad"; from a Pixel folder expect a still plus a 3 s clip
  (not verified). Default: send both with one base name, so nothing is lost.
- **Edited photos** have the original (`.photo`) and the edit (`.fullSizePhoto`). Send the
  edit, as the user sees it, once; later edits are ignored ("deliver once", as on Android).
- **Identity:** names like `IMG_0001.HEIC` repeat and dates are not unique. The ledger key is
  the asset's `localIdentifier` plus resource type, stable on that device. Survival of a
  restore to a new phone is not verified, so the hub also drops exact duplicates by SHA-256.

## 5. Local network and pairing

- **Info.plist:** `NSLocalNetworkUsageDescription` (the prompt text); `NSBonjourServices` =
  `_photosync._tcp`; and ATS (App Transport Security, iOS's HTTPS rules) with
  `NSAllowsLocalNetworking`. Browsing one listed service type needs no multicast entitlement.
- **The prompt** appears on first local access; in the background, before an answer, access
  fails silently. So pairing browses in the foreground (Bonjour, Apple's mDNS, sees the NSD
  service; `PLAN.md`'s fallbacks still apply), and the extensions inherit the answer.
- **ATS:** `NSAllowsLocalNetworking` lifts ATS for IP addresses and `.local` names, so the app
  can check the hub's pinned certificate itself. Uploads run by the system cannot be
  pinned: they need a certificate the phone trusts, or plain HTTP (decision 5).
- **Pairing is unchanged:** type the 6-digit code once over TLS and pin the hub on first use.
  The long-lived key goes in the Keychain, shared with the extensions through an App Group
  (storage shared by an app and its extensions). Free accounts allow both.
- **No Wi-Fi name:** reading it needs an entitlement free accounts lack, plus location access.
  iOS can still tell Wi-Fi from mobile data. "Home" = "the hub answers", as in `PLAN.md`.

## 6. Widgets

| Android | iOS (WidgetKit) | Notes |
|---|---|---|
| Week 4×2 | `systemMedium` | Same layout |
| Progress / Count / All safe / states 2×2 | `systemSmall`, one widget that picks its look by state | Optional setting: ring or big number |
| — | Lock Screen `accessoryCircular` / `Rectangular` / `Inline` (iOS 16) | Ring and count; the system draws them in one colour |
| — | StandBy (iOS 17) reuses `systemSmall` | Nothing to build |
| Quick-settings tile | Control Center `ControlWidgetToggle` (iOS 18) | Pause / Resume |
| Hub 4×2 | — | The hub stays Android |

- **Pause / Resume** in the widget is `Toggle(isOn:intent:)` with an App Intent (an action
  the system runs for the widget), iOS 17+. Pause sets a shared flag and cancels queued jobs.
- **Refresh budget:** an often-viewed widget gets 40–70 reloads a day (every 15–60 min),
  entries at least ~5 min apart; reloads are free while the app is in front and after a widget
  button. So numbers change when the app or the extension writes a new `Snapshot` and asks for
  a reload (from the extension: not verified). A relative "updated 2 h ago" ticks by itself.
- **Design changes:** the system owns corner radius and margins; the tile colour becomes the
  `containerBackground`. Tinted and clear Home Screen styles (iOS 18/26) recolour widgets, so
  "All safe" is not always green: ticks, rings and numbers must read in one colour. SwiftUI
  draws them (no Canvas bitmaps); the fonts ship in the widget extension. "Waiting for home"
  = not on Wi-Fi; "can't find the hub" = on a Wi-Fi with no answer (also in a café).

## 7. What iOS asks of `PLAN.md` (proposed changes)

1. **Uploads follow the IETF "Resumable Uploads for HTTP" draft:** `OPTIONS` → `Upload-Limit`,
   `104` + `Location` during the upload, `HEAD` for the offset, `PATCH` to append, at the
   version iOS 18.1+ speaks (interop 6). Apple's page shows older header names, so log real
   headers in I1. Android uses the same endpoints.
2. **Raw file body**, metadata in headers: phone, source key, kind, pair key, original name,
   date taken, size. Phone-side SHA-256 is optional; the hub computes it and returns it.
3. **Auth is a header token** from the pairing key, valid for days (jobs run long after they
   are made). No `401` challenges (they wake the app), no client certificates, no sessions.
4. **Results are status plus headers only:** `201`/`200`, "already have" counts as success;
   `507` + `Retry-After` + a reason header when full; `403`, not `401`, for an unknown phone.
5. **Ledger key = (phone, source key, kind):** an opaque key chosen by the phone (relative
   path on Android, asset id on iOS); size and SHA-256 check content; no modified time.
6. **Pair-aware clash renaming** (both halves of a Live Photo keep one base name); the hub
   sets each file's modified time to the date taken.
7. **Freeze** the Bonjour type `_photosync._tcp`, its TXT keys (hub id, certificate
   fingerprint, protocol version) and a default port. Accept parallel uploads from one phone.
8. **TLS, chosen after I1.** (a) A small hub root issues the server certificate (IP and name
   in SAN, ≤ 825 days, serverAuth EKU, ECDSA P-256/SHA-256, TLS 1.2+). The hub offers the root
   as an iOS profile and all phones pin the root, so IP changes need no re-pairing. (b) A
   plain-HTTP upload port on the LAN, guarded by the change-3 token.
9. **Stable hub address:** job URLs are fixed at creation, so setup asks for a DHCP
   reservation (fixed IP in the router) for the Pixel; failed jobs retry at the new address.
10. **"What do you lack"** takes iOS keys, pages a few hundred at a time and answers fast
    (the extension has a runtime limit).
11. **Migration:** the hub lists (name, size, date) of an old folder, so a phone can mark
    those as sent.
12. **Phase 4 keeps Syncthing-Fork on the Pixel** while any iPhone still uses the interim.

## 8. Interim without an iOS app

| Option | Cost | Background | Hub work |
|---|---|---|---|
| **Möbius Sync** (Syncthing for iOS) | Free to 20 MB, then a one-time unlock (about USD 5, not verified) | Short runs; "may take 24 hours to start", then "1–2h of sync activity per day" | None: a Receive Only folder with `ignoreDelete`, as for Android |
| PhotoSync Premium | Subscription or lifetime (about USD 15–30, not verified) | On arriving home ("Always" location access), or nightly on the charger; must stay in the app switcher | A WebDAV, SMB or SFTP server on the Pixel |
| Shortcuts automation (on joining Wi-Fi or plugging in) | Free | Runs without asking, but re-sends recent photos, has no backlog, big videos untested | A raw HTTP upload endpoint; Shortcuts cannot pin certificates |

Do **Möbius Sync first**: no code, Send Only photo folders (v1.27.9+), and iPhone deletes
don't matter because the Pixel ignores them. It is a bridge, not the end state (no widget, no
ledger, keeps Syncthing on the Pixel). With one iPhone and good results, postponing the
native app is reasonable: its background delivery would be similar.

## 9. Tooling and distribution from Linux

A Mac (Xcode) is needed to build the ExtensionKit photo extension, to upload to TestFlight,
and for the Resource Upload Test Mode (Xcode running the build on the phone). Without one:
- **xtool** ("cross-platform Xcode replacement") builds SwiftPM apps and widgets on Linux,
  installs over USB and signs with a free or paid Apple ID (needs a downloaded `Xcode.xip`).
  **No ExtensionKit yet** (issue #138 open), so no upload extension. Good for screens, widgets.
- **GitHub Actions macOS runners:** free in public repositories; private ones get $12 a month
  of use on the Free plan, at $0.062 per macOS minute.
- **A rented cloud Mac** for occasional Xcode work, e.g. a Scaleway Mac mini at about €0.22 an
  hour with a 24-hour minimum (not verified). No on-device Test Mode.

Text-only layout next to `app/` (XcodeGen turns a YAML file into the Xcode project on CI):

```
ios/project.yml   XcodeGen spec (supports `extensionkit-extension`); CI makes the .xcodeproj
ios/Core/         Swift package: protocol client, ledger cache, Snapshot; `swift test` on Linux
ios/App/          SwiftUI screens, PhotoKit, background tasks
ios/Widgets/      WidgetKit, App Intents, Control Center toggle
ios/Upload/       PhotoKit background upload extension
ios/ci/build.sh   xcodegen generate, xcodebuild archive and export; run by GitHub Actions
```

| Install route | Cost | Upkeep |
|---|---|---|
| Free Apple ID (Personal Team), via Xcode or xtool | $0 | Profiles last 7 days. 3 devices, 3 apps per device, 10 App IDs per 7 days (app plus 2 extensions = 3). Free signing of the photo extension is not verified |
| Free Apple ID with AltStore or SideStore | $0 | Same limits. SideStore re-signs on the phone over Wi-Fi; a missed weekly refresh stops sending |
| Paid program, ad hoc (signed for listed devices) | $99 / year | Register each device, renew yearly (not verified), install over USB |
| **Paid program, TestFlight internal testers** | **$99 / year** | **Builds last 90 days. Family members become App Store Connect users and update in the TestFlight app; CI uploads a build at least quarterly** |

Recommended: **paid program with TestFlight.** An app that must send unattended can't rely
on a weekly re-sign.

## 10. Phases

- **I0. Interim** (small, any time). Möbius Sync into today's Pixel setup. Check: a morning
  photo is in `DCIM/Sync/<iPhone>-…` and backed up by the next day without opening Möbius.
- **I1. Background spike** (medium, before `PLAN.md` phase 2 freezes the protocol; needs the
  paid program for CI signing, or a Mac). A bare app plus upload extension against a test
  server on the Linux PC (tusd is known to work with URLSession); plain HTTP, then HTTPS with
  a trusted root. Check: 7 days with the app closed, every new photo and video arrives; log
  times, draft headers, the `BackgroundUploadURLBase` used, iCloud Photos off and on.
- **I2. Swift core** (medium, after `PLAN.md` phase 2). Check: `swift test` on Linux passes
  against the phase-2 Java hub: kill and resume, hub full, "already have".
- **I3. iOS app** (large, after `PLAN.md` phase 3's trial). Pairing, albums, three sending
  paths, Pause. Check: a week of normal use; the app's counts match the hub ledger.
- **I4. Widgets** (medium). Week, small, Lock Screen, Control Center. Check: on-device
  screenshots in every state, tinted Home Screen included.
- **I5. Rollout** (small, with `PLAN.md` phase 4). TestFlight invites; move each iPhone off
  Möbius with change 11. Check: nothing sent twice; only then Syncthing leaves the Pixel.

## 11. Risks to test early

- **The extension may not reach a LAN server,** or may reject a LAN `BackgroundUploadURLBase`.
- **The iCloud Photos bug** may block it. Test with each family member's real settings.
- **Real timing** may be worse than daily. Measure for a week before designing around it.
- **Draft drift:** iOS has changed resumable-upload versions before (17.x, 18.0, 18.1+).
- **Google Photos on the Pixel:** `adb push` a HEIC + MOV Live Photo pair and an HEVC video
  into a backed-up folder. Does it show one item or two, with the right dates? Do this now;
  it takes ten minutes.
- **The hub's IP changes** and queued jobs fail: needs the DHCP reservation and a retry.

## 12. Open decisions

1. Which iPhones or iPads (models, iOS versions)? Background sending needs iOS 26.1+, which
   runs on iPhone 11 / SE (2nd gen) and later (press reports); older ones get "open the app".
2. A Mac to own or borrow? With one, I1 is quick and the extension can be debugged in Test
   Mode. Without one: CI builds and debugging from logs.
3. Paid Developer Program ($99 a year)? Without it, apps need re-signing every week, there is
   a 3-device cap, and possibly no upload extension. The interim then becomes the end state.
4. Start the Möbius interim now? If so, accept change 12 for `PLAN.md` phase 4.
5. System-run uploads: a hub root installed as a profile on each iPhone (encrypted; a
   one-time trip through Settings), or plain HTTP on the LAN with signed tokens (simpler;
   photos unencrypted on home Wi-Fi).
6. Live Photos: still plus clip (default), or still only, after the Google Photos test.
7. Edited photos: the edit (default), or the original.
8. Take section 7 into `PLAN.md` before its phase 2 starts.

## Sources

- PhotoKit upload: https://developer.apple.com/documentation/photokit/uploading-asset-resources-in-the-background · https://developer.apple.com/documentation/photos/phbackgroundresourceuploadextension · https://developer.apple.com/documentation/photos/phassetresourceuploadjob
- Apple forums: https://developer.apple.com/forums/thread/809775 (timing, Live Photos) · https://developer.apple.com/forums/thread/822256 (iCloud bug) · https://developer.apple.com/forums/thread/819034 (iCloud downloads) · https://developer.apple.com/forums/thread/818566 (no chunking) · https://developer.apple.com/forums/thread/28713 (background challenges)
- Field reports: https://github.com/TinusNL/iOS-PhotoKit-Backup · https://github.com/johannesvogel/photo-uploader-for-immich · https://github.com/toots/tsync/pull/202 · https://9to5mac.com/2025/10/24/ios-26-1-third-party-photos-backup-background/
- Background tasks: https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask · https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados · https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app
- Resumable uploads: https://developer.apple.com/videos/play/wwdc2023/10006/ · https://github.com/tus/rufh-implementations
- Network and TLS: https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy · https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking · https://developer.apple.com/documentation/security/preventing-insecure-network-connections · https://support.apple.com/en-us/103769
- Photos: https://developer.apple.com/documentation/photos/phassetresourcetype · https://support.google.com/photos/answer/6193313
- WidgetKit: https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date · https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities · https://developer.apple.com/documentation/widgetkit/controlwidgettoggle
- Accounts: https://developer.apple.com/help/account/reference/supported-capabilities-ios · https://developer.apple.com/support/compare-memberships/ · https://developer.apple.com/programs/ · https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview · https://faq.altstore.io/altstore-classic/app-ids
- Tools: https://github.com/xtool-org/xtool (docs `Appex.md`, `Installation-Linux.md`, issue #138) · https://github.com/yonaskolb/XcodeGen/blob/master/Docs/ProjectSpec.md · https://docs.github.com/en/billing/reference/actions-runner-pricing · https://docs.github.com/en/billing/concepts/product-billing/github-actions
- Interim apps: https://mobiussync.com/faq · https://www.photosync-app.com/support/ios/answers/how-do-i-automatically-transfer-photos-once-a-day · https://support.apple.com/guide/shortcuts/create-a-new-personal-automation-apdfbdbd7123/ios
- Devices (press): https://www.macrumors.com/2026/06/08/ios-27-supports-iphone-11-newer/
