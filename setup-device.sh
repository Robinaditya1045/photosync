#!/usr/bin/env bash
# Prepare one Android device (connected over USB, USB debugging on) for the
# Syncthing -> Pixel -> Google Photos pipeline.
#
#   ./setup-device.sh --role pixel  [--serial SERIAL] [--apk FILE]
#   ./setup-device.sh --role source [--serial SERIAL] [--apk FILE] [--cutoff all|YYYY-MM] [--no-widget]
#
# What it does:
#   * installs Syncthing-Fork (from --apk, or downloads the latest GitHub release)
#   * grants storage access and exempts it from battery optimisation
#   * pixel:  turns on Smart Storage so already-backed-up photos are removed from the
#             Pixel automatically when it runs low (keeps the backlog flowing)
#   * source: lists where the existing photos/videos on the device are, and pushes a
#             .stignore into DCIM/Camera and the Screenshots folder (DCIM/ or Pictures/).
#             --cutoff all (default) sends existing AND new photos;
#             --cutoff YYYY-MM sends only photos from that month on
#   * source: sync switch + status widget (skip with --no-widget): builds (if needed)
#             and installs the Photo Sync app from widget/, and adds Syncthing's
#             "Force run" quick-settings tile
#   * prints the manual steps that adb cannot do (pairing, Google Photos settings)
set -euo pipefail

PKG=com.github.catfriend1.syncthingfork
REPO=researchxxl/syncthing-android
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=/storage/emulated/0
WIDGET_APK=$HERE/apk/photosync-widget.apk
SMART_STORAGE_DAYS=30   # Pixel Smart Storage accepts 30, 60 or 90

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }

role="" serial="" apk="" cutoff="" widget=1
while [[ $# -gt 0 ]]; do
  case $1 in --role|--serial|--apk|--cutoff)
    [[ $# -ge 2 ]] || { echo "$1 needs a value" >&2; exit 1; } ;;
  esac
  case $1 in
    --role)      role=$2; shift 2 ;;
    --serial)    serial=$2; shift 2 ;;
    --apk)       apk=$2; shift 2 ;;
    --cutoff)    cutoff=$2; shift 2 ;;
    --no-widget) widget=0; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done
[[ $role == pixel || $role == source ]] || { echo "--role pixel|source is required" >&2; exit 1; }
[[ -z $cutoff || $role == source ]] || { echo "--cutoff only applies to --role source" >&2; exit 1; }
[[ $role == source && -z $cutoff ]] && cutoff=all
[[ -z $cutoff || $cutoff == all || $cutoff =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]] \
  || { echo "--cutoff must be 'all' or YYYY-MM (for example 2026-11)" >&2; exit 1; }
[[ $role == source ]] || widget=0

ADB=(adb)
[[ -n $serial ]] && ADB+=(-s "$serial")
sh() { "${ADB[@]}" shell "$@" | tr -d '\r'; }
installed() { sh pm list packages "$1" | grep -qx "package:$1"; }

# install_github_apk <package> <owner/repo> <asset-url-regex> [local-apk]
install_github_apk() {
  local pkg=$1 repo=$2 re=$3 file=${4:-} url
  if installed "$pkg"; then echo "==> $pkg already installed"; return 0; fi
  if [[ -z $file ]]; then
    url=$(curl -fsSL "https://api.github.com/repos/$repo/releases/latest" \
          | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)"/\1/' \
          | grep -E "$re" | head -n1) || true
    [[ -n $url ]] || { echo "Could not find an APK matching /$re/ in the latest $repo release" \
                            "(no network, or GitHub's rate limit? Pass one with --apk)" >&2; return 1; }
    mkdir -p "$HERE/apk"
    file="$HERE/apk/$(basename "$url")"
    if [[ ! -f $file ]]; then
      echo "==> downloading $url"
      curl -fL -o "$file.part" "$url" && mv "$file.part" "$file" || return 1
    fi
  fi
  adb_install "$file"
}

adb_install() {  # apk: install/upgrade, with a hint when the phone blocks it
  local file=$1 out rc=0
  echo "==> installing $(basename "$file") (watch the phone: some ask you to tap Install)"
  out=$("${ADB[@]}" install -r "$file" 2>&1) || rc=$?
  echo "$out" | sed 's/^/    /'
  if (( rc != 0 )) && grep -q -E 'INSTALL_FAILED_USER_RESTRICTED|Install canceled by user' <<<"$out"; then
    cat >&2 <<'EOF'
    The phone blocked the install. On Xiaomi/Redmi/POCO (MIUI/HyperOS):
      Settings > Additional settings > Developer options >
        turn ON "Install via USB" (needs a Mi account sign-in, sometimes SIM + mobile data)
        turn ON "USB debugging (Security settings)"
    Then re-run, and tap "Install" on the phone's pop-up within ~10 seconds.
    (Other brands: accept the install prompt on the phone.)
EOF
  fi
  return "$rc"
}

# --- device check -----------------------------------------------------------
n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
if [[ -z $serial && $n -ne 1 ]]; then
  echo "Expected exactly 1 connected device, found $n. Use --serial (see 'adb devices')." >&2
  exit 1
fi
model=$(sh getprop ro.product.model)
sdk=$(sh getprop ro.build.version.sdk)
echo "==> $model (Android SDK $sdk), role: $role"

# --- install Syncthing-Fork ---------------------------------------------------
install_github_apk "$PKG" "$REPO" '_release_v[0-9.]+\.apk$' "$apk" || exit 1

# --- permissions & battery ------------------------------------------------------
grant() {  # permission: quiet, with a hint instead of a stack trace
  local out
  out=$(sh pm grant "$PKG" "android.permission.$1" 2>&1) || true
  if grep -q -E 'Exception|Error' <<<"$out"; then
    echo "    could not grant $1 over USB; allow it when Syncthing asks."
    echo "    (Xiaomi: Developer options > turn ON \"USB debugging (Security settings)\")"
  fi
}
echo "==> granting storage access"
if (( sdk >= 30 )); then
  sh appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
  [[ $(sh appops get "$PKG" MANAGE_EXTERNAL_STORAGE) == *allow* ]] && echo "    ok" \
    || echo "    not granted: allow \"All files access\" when Syncthing asks"
else
  grant READ_EXTERNAL_STORAGE
  grant WRITE_EXTERNAL_STORAGE
fi
(( sdk >= 33 )) && grant POST_NOTIFICATIONS

echo "==> exempting from battery optimisation"
sh dumpsys deviceidle whitelist +"$PKG" >/dev/null
sh dumpsys deviceidle whitelist | grep -q "$PKG" && echo "    ok" || echo "    WARNING: not whitelisted, do it in Settings > Apps > Syncthing > Battery"

# --- pixel: automatic space reclaim -----------------------------------------------
if [[ $role == pixel ]]; then
  echo "==> enabling Smart Storage (removes backed-up photos when storage runs low)"
  sh settings put secure automatic_storage_manager_enabled 1 || true
  sh settings put secure automatic_storage_manager_days_to_retain "$SMART_STORAGE_DAYS" || true
  echo "    enabled=$(sh settings get secure automatic_storage_manager_enabled)" \
       "days=$(sh settings get secure automatic_storage_manager_days_to_retain)"
  echo "    (check Settings > Storage > Smart Storage shows ON)"
  echo "==> Pixel storage:"
  sh df -h /data | tail -n 1 | awk '{ printf "    size %s, free %s (%s used)\n", $2, $4, $5 }'
fi

# --- source: inventory of existing photos ----------------------------------------
if [[ $role == source ]]; then
  echo "==> Existing photos/videos on this device (files per folder, top 20):"
  sh "find $ROOT \\( -path $ROOT/Android -o -name '.*' \\) -prune -o -type f \\(" \
     "-iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.heic' -o -iname '*.heif'" \
     "-o -iname '*.webp' -o -iname '*.gif' -o -iname '*.dng' -o -iname '*.mp4' -o -iname '*.mov'" \
     "-o -iname '*.3gp' -o -iname '*.mkv' \\) -print 2>/dev/null; true" \
    | sed "s|/[^/]*\$||; s|^$ROOT/\{0,1\}||" | sort | uniq -c | sort -rn | head -n 20 \
    | awk '{ n = $1; $1 = ""; d = substr($0, 2); printf "    %7d  %s\n", n, (d == "" ? "(top level)" : d) }' || true
  # Default folders: camera + screenshots (Xiaomi/Samsung keep screenshots under DCIM/)
  defaults=()
  for d in DCIM/Camera DCIM/Screenshots Pictures/Screenshots; do
    sh "[ -d '$ROOT/$d' ] && echo yes" | grep -q yes && defaults+=("$d")
  done
  echo "    Only folders you share in Syncthing get sent. The defaults on this device:"
  echo "    ${defaults[*]:-(none found)}. Add any other folder above that holds photos"
  echo "    you care about (WhatsApp stays excluded)."
  if (( ${#defaults[@]} )); then
    echo "==> Size of the default folders (what the Pixel will receive):"
    sh "cd $ROOT && du -sh ${defaults[*]} 2>/dev/null; true" | sed 's|^|    |'
  fi

  # ignore patterns
  tmp=$(mktemp)
  "$HERE/gen-stignore.sh" "$cutoff" > "$tmp"
  for d in "${defaults[@]}"; do
    if "${ADB[@]}" push "$tmp" "$ROOT/$d/.stignore" >/dev/null 2>&1; then
      echo "==> .stignore ($cutoff) -> $d"
    else
      echo "    WARNING: could not write $d/.stignore. Paste the patterns from" \
           "'./gen-stignore.sh $cutoff' into the folder's Ignore patterns in Syncthing." >&2
    fi
  done
  rm -f "$tmp"
fi

# --- source: sync switch + status widget ------------------------------------------
if (( widget )); then
  echo "==> sync switch & status widget (Photo Sync app)"
  [[ -f $WIDGET_APK ]] || "$HERE/widget/build.sh"
  adb_install "$WIDGET_APK" || exit 1
  # Syncthing's own 3-state tile: following run conditions / forced to run / forced to stop
  sh cmd statusbar add-tile "$PKG/com.nutomic.syncthingandroid.service.QuickSettingsTileForce" >/dev/null 2>&1 \
    && echo "==> added the Syncthing 'Force run' quick-settings tile" \
    || echo "    (add the Syncthing 'Force run' tile by editing Quick Settings)"
fi

# --- launch & remaining manual steps --------------------------------------------
sh monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo
echo "==> Remaining manual steps on this device (see SETUP.md):"
if [[ $role == pixel ]]; then
  cat <<'EOF'
  1. Syncthing: Settings > Run conditions > Wi-Fi only + your home SSID.
  2. Accept each folder offered by the source devices. In the accept screen set
       path:        /storage/emulated/0/DCIM/Sync/<DeviceName>-<Folder>
       Folder Type: Receive Only
  3. Straight after accepting EACH folder (it starts syncing at once):
     Syncthing menu > Web GUI > Actions > Advanced > Folders > <folder>:
       Type = receiveonly, Ignore Delete = ON, Min Disk Free = 15 %,
       Order = newestFirst  -> Save
  4. Google Photos > Settings > Backup: ON, quality Original,
     Back up device folders > enable every Sync/... folder.
  5. Keep the Pixel on the charger and on Wi-Fi.
EOF
else
  cat <<EOF
  1. Syncthing: Settings > Run conditions > Wi-Fi only + your home SSID.
  2. Add the Pixel as a device (scan its QR from Pixel > Syncthing > Show device ID).
  3. Add folders ${defaults[*]:-DCIM/Camera} (plus any extra folder from the
     list above): type Send Only, shared with the Pixel only.
     The ignore patterns are already in place; check the folder's "Ignore patterns".
  4. Google Photos on THIS device: Settings > Backup > OFF.
EOF
  if (( widget )); then cat <<'EOF'
  Sync switch & status widget:
  5. Open the "Photo Sync" app (opened for you now) and do its 3 steps:
     allow switching in Syncthing, paste the API key, add the widget.
EOF
    sh am start -n home.photosync/.SetupActivity >/dev/null 2>&1 || true
  fi
fi
