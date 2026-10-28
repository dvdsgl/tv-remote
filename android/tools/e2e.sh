#!/usr/bin/env bash
# End-to-end test: the Kotlin Companion client (on the JVM) against pyatv's fake Apple TV.
# Needs uv and git. PYATV_SRC can point at an existing pyatv v0.18.0 checkout.
set -euo pipefail
cd "$(dirname "$0")/.."
src=${PYATV_SRC:-${TMPDIR:-/tmp}/pyatv-v0.18.0}
[ -d "$src/tests/fake_device" ] || git clone -q --depth 1 --branch v0.18.0 https://github.com/postlund/pyatv "$src"
log=$(mktemp)
uv run --no-project -q --with pyatv==0.18.0 --with srptools==1.0.1 python -I -u tools/fake_tv.py "$src" >"$log" 2>"$log.err" &
pid=$!
trap 'kill $pid 2>/dev/null; rm -f "$log" "$log.err"' EXIT
for _ in $(seq 120); do grep -q '^FAKE_TV=' "$log" && break; sleep 0.5; done
grep -q '^FAKE_TV=' "$log" || { cat "$log.err"; echo "fake TV didn't start"; exit 1; }
export FAKE_TV=$(sed -n 's/^FAKE_TV=//p' "$log")
./gradlew --no-daemon -q testDebugUnitTest --tests '*CompanionE2ETest*'
echo "E2E passed against $FAKE_TV"
