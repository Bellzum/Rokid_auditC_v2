#!/bin/sh

for arg in "$@"; do
    if [ "$arg" = "/data/local/tmp/scrcpy-server.jar" ]; then
        exit 0
    fi
done

exec "${REAL_ADB:-adb}" "$@"
