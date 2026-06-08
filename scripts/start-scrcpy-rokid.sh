#!/bin/sh
set -eu

REAL_ADB="${REAL_ADB:-$(command -v adb)}"
SCRCPY_BIN="${SCRCPY_BIN:-$(command -v scrcpy)}"
SCRCPY_SERVER="${SCRCPY_SERVER:-$(brew --prefix scrcpy)/share/scrcpy/scrcpy-server}"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

"$REAL_ADB" wait-for-device
"$REAL_ADB" reverse tcp:8000 tcp:8000
"$REAL_ADB" shell 'cat > /data/local/tmp/scrcpy-server.jar' < "$SCRCPY_SERVER"

REAL_ADB="$REAL_ADB" \
ADB="$SCRIPT_DIR/adb-scrcpy-wrapper.sh" \
"$SCRCPY_BIN" --no-cleanup "$@"
