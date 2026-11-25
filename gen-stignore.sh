#!/usr/bin/env bash
# Generate a Syncthing .stignore for a camera/screenshot folder.
#
#   ./gen-stignore.sh all              # sync everything, existing photos too (default)
#   ./gen-stignore.sh 2026-11          # only sync media dated Nov 2026 or later
#   ./gen-stignore.sh 2026-11 > .stignore
#
# Cut-off works on the date embedded in camera filenames, e.g.
#   IMG_20261123_101500.jpg  VID_20261123_...mp4  PXL_20261123_...jpg
#   Screenshot_20261123-...png  20261123_101500.jpg (Samsung)
#   Screenshot_2026-11-23-10-15-00-123_app.jpg (Xiaomi: dashes in the date)
# Older files are ignored, so photos that are already backed up are not
# re-copied to the Pixel. Files without such a date in their name are ignored too.
set -euo pipefail

PREFIXES=("IMG_" "VID_" "PXL_" "MVIMG_" "Screenshot_" "")   # "" = Samsung style
YEARS_AHEAD=10   # how many future years to whitelist, so you rarely need to regenerate

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }
[[ ${1:-} == -h || ${1:-} == --help ]] && { usage; exit 0; }
[[ $# -eq 1 ]] || { usage >&2; exit 1; }

junk() {
  cat <<'JUNK'
// Android/Syncthing junk: never sync
.trashed-*
.pending-*
.thumbnails
.nomedia
JUNK
}

if [[ $1 == all ]]; then
  echo "// Backlog mode: sync everything except junk"
  junk
  exit 0
fi

[[ $1 =~ ^([0-9]{4})-([0-9]{2})$ ]] || { usage >&2; exit 1; }
year=${BASH_REMATCH[1]}
month=$((10#${BASH_REMATCH[2]}))
(( month >= 1 && month <= 12 )) || { echo "bad month: $1" >&2; exit 1; }

echo "// Cut-off: only media dated ${year}-$(printf %02d "$month") or later is synced"
junk
for p in "${PREFIXES[@]}"; do
  # remaining months of the cut-off year, date written as 202611 or 2026-11
  for ((m = month; m <= 12; m++)); do
    printf '!%s%d%02d*\n' "$p" "$year" "$m"
    printf '!%s%d-%02d*\n' "$p" "$year" "$m"
  done
  # following years (either date style)
  for ((y = year + 1; y <= year + YEARS_AHEAD; y++)); do
    printf '!%s%d*\n' "$p" "$y"
  done
done
echo "// Ignore everything else (older files, odd names)"
echo "*"
