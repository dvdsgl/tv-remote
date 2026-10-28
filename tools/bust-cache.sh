#!/usr/bin/env bash
# GitHub Pages caches assets, so the site's pages refer to their local assets with a content
# hash (demo.css?v=1a2b3c4d). Run this before committing site changes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
for asset in demo.css icon.svg icon.png; do
  hash=$(sha256sum "$root/docs/$asset" | cut -c1-8)
  for html in "$root/docs/index.html" "$root/docs/embed.html"; do
    sed -i -E "s#(href|src)=\"$asset(\?v=[0-9a-f]*)?\"#\1=\"$asset?v=$hash\"#g" "$html"
  done
done
