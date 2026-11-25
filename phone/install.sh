#!/data/data/com.termux/files/usr/bin/bash
# LEGACY: the Termux version of the sync switch. The Photo Sync app in widget/ replaced
# it, and setup-device.sh no longer copies or installs this. Kept for reference only.
#
# To use it anyway, copy this folder to /sdcard/Download/photosync/ by hand, then run
# INSIDE Termux on a source phone:
#
#   bash /sdcard/Download/photosync/install.sh
#
# Copy the Syncthing API key first (Syncthing > Settings > Syncthing Options >
# tap "Syncthing API Key"); the installer picks it up from the clipboard.
# Installs the `photosync` command and four home-screen widget buttons:
# Sync ON, Sync OFF, Sync AUTO, Sync status.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
[[ -d $PREFIX/bin ]] || { echo "Run this inside the Termux app." >&2; exit 1; }

echo "==> installing curl, jq and termux-api"
pkg install -y curl jq termux-api

echo "==> installing photosync"
install -m 700 "$HERE/photosync" "$PREFIX/bin/photosync"
sed -i "1s|.*|#!$PREFIX/bin/bash|" "$PREFIX/bin/photosync"   # this phone's Termux path

echo "==> creating widget buttons"
dir=$HOME/.shortcuts/tasks          # tasks/ = run in background, no terminal window
mkdir -p "$dir"
chmod 700 "$HOME/.shortcuts" "$dir"
for b in "Sync ON:on" "Sync OFF:off" "Sync AUTO:auto" "Sync status:status"; do
  f="$dir/${b%%:*}"
  printf '#!%s/bin/bash\nexec %s/bin/photosync %s\n' "$PREFIX" "$PREFIX" "${b##*:}" > "$f"
  chmod 700 "$f"
done

echo "==> Syncthing API key"
photosync setkey || echo "    (set it later with: photosync setkey)"

echo "==> current status"
photosync status quiet || true

cat <<'EOF'

Done. Add the widget: long-press the home screen > Widgets > Termux:Widget.
It lists: Sync AUTO, Sync OFF, Sync ON, Sync status.
The status notification has ON / OFF / AUTO buttons as well; tap it to refresh.
EOF
