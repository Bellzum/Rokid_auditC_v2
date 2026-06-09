#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ROOT_DIR/.android-sdk"
export ANDROID_SDK_ROOT="$ROOT_DIR/.android-sdk"

DEVICE_ID="${1:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
MONITOR_PID_FILE="$ROOT_DIR/.auditc_tunnel_monitor.pid"

if [ -z "$DEVICE_ID" ]; then
  echo "No ADB device detected. Connect the glasses and run 'adb devices' first."
  exit 1
fi

echo "Using device: $DEVICE_ID"

restore_tunnel() {
  adb -s "$DEVICE_ID" reverse tcp:8000 tcp:8000 >/dev/null
}

if ! lsof -i tcp:8000 >/dev/null 2>&1; then
  echo "Starting FastAPI backend on port 8000..."
  python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload > "$ROOT_DIR/auditc-backend.log" 2>&1 &
  echo $! > "$ROOT_DIR/.auditc_backend.pid"
  sleep 4
else
  echo "Backend already running on port 8000."
fi

echo "Setting up USB tunnel..."
restore_tunnel

echo "Building Rokid app..."
cd "$ROOT_DIR/auditc-rokid-android"
./gradlew assembleDebug

echo "Installing APK..."
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk

echo "Launching app..."
adb -s "$DEVICE_ID" shell am start -n com.auditc.glasses/.MainActivity

if [ -f "$MONITOR_PID_FILE" ]; then
  OLD_MONITOR_PID="$(cat "$MONITOR_PID_FILE" 2>/dev/null || true)"
  if [ -n "$OLD_MONITOR_PID" ] && kill -0 "$OLD_MONITOR_PID" 2>/dev/null; then
    kill "$OLD_MONITOR_PID" 2>/dev/null || true
  fi
  rm -f "$MONITOR_PID_FILE"
fi

(
  last_state="connected"
  while true; do
    if adb devices | awk 'NR>1 && $1=="'"$DEVICE_ID"'" && $2=="device" { found=1 } END { exit found ? 0 : 1 }'; then
      if [ "$last_state" != "connected" ]; then
        restore_tunnel
        echo "Glasses reconnected — tunnel restored"
      fi
      last_state="connected"
    else
      last_state="disconnected"
    fi
    sleep 10
  done
) &

MONITOR_PID=$!
echo "$MONITOR_PID" > "$MONITOR_PID_FILE"
echo "Tunnel monitor running in background with PID $MONITOR_PID"
echo "Audit C is running on the glasses."
