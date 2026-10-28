#!/usr/bin/env bash
# Frames raw emulator screenshots as device mockups for the website and the README:
#
#   tools/frame.sh        # docs/screenshots/*.png -> docs/*.png
#
# Files named fold-*.png get an unfolded foldable; everything else a tall phone.
# Needs headless Chromium and ImageMagick.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
chrome="$(command -v chromium || command -v google-chrome-stable)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

for shot in "$root"/docs/screenshots/*.png; do
  name="$(basename "$shot")"
  read -r iw ih < <(magick identify -format '%w %h\n' "$shot")
  case "$name" in
    fold-*) W=720; bezel=10; outer=34; inner=24; cam="right: 26px; left: auto;" ;;
    *)      W=390; bezel=11; outer=62; inner=51; cam="left: 50%; margin-left: -6px;" ;;
  esac
  sh=$(( (W * ih + iw - 1) / iw ))
  cat > "$tmp/page.html" <<HTML
<!doctype html><html><head><style>
html, body { margin: 0; background: transparent; overflow: hidden; }
body { padding: 6px 10px 6px 6px; }
.device { position: relative; width: ${W}px; padding: ${bezel}px; border-radius: ${outer}px;
  background: linear-gradient(150deg, #3a3a3e, #121214 30%, #0b0b0c 70%, #2a2a2e);
  box-shadow: inset 0 0 0 1px #5a5a60, inset 0 0 0 3px #0a0a0b; }
.key { position: absolute; right: -4px; width: 4px; border-radius: 0 3px 3px 0;
  background: linear-gradient(90deg, #1c1c1f, #4a4a50); }
.screen { position: relative; height: ${sh}px; border-radius: ${inner}px; overflow: hidden; background: #000; }
.screen img { display: block; width: 100%; }
.cam { position: absolute; top: 13px; ${cam} width: 12px; height: 12px; border-radius: 50%;
  background: radial-gradient(circle at 40% 40%, #1b2030, #050506 60%); box-shadow: 0 0 0 1.5px #0d0d0f; }
</style></head><body><div class="device">
<div class="key" style="top:150px;height:70px"></div><div class="key" style="top:240px;height:44px"></div>
<div class="screen"><img src="$name"><div class="cam"></div></div>
</div></body></html>
HTML
  cp "$shot" "$tmp/$name"
  "$chrome" --headless --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=3 \
    --default-background-color=00000000 --window-size=$((W + 2 * bezel + 20)),$((sh + 2 * bezel + 12)) \
    --screenshot="$tmp/out.png" "file://$tmp/page.html" 2>/dev/null
  magick "$tmp/out.png" -trim +repage -strip "$root/docs/$name"
  echo "docs/$name"
done

# The social card and the touch icon.
"$chrome" --headless --no-sandbox --disable-gpu --hide-scrollbars --window-size=1200,630 --force-device-scale-factor=2 \
  --screenshot="$root/docs/og.png" "file://$root/tools/og.html" 2>/dev/null
magick "$root/docs/og.png" -strip "$root/docs/og.png"
magick -background none -density 600 "$root/docs/icon.svg" -resize 180x180 -strip "$root/docs/icon.png"
echo "docs/og.png docs/icon.png"
