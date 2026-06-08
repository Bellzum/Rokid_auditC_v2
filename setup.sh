#!/bin/bash
set -e

echo "Setting up Audit C..."

pip3 install fastapi uvicorn reportlab requests openai-whisper python-multipart --break-system-packages

export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$PWD/.android-sdk"
export ANDROID_SDK_ROOT="$PWD/.android-sdk"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "Setup complete! Run ./run_auditc.sh to start."
