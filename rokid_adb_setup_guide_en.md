# Rokid Glasses — ADB Setup and USB Connection Quick Start

Use this guide when connecting a preconfigured Rokid Glasses device to a Mac without an Android phone.

## Step 1 — Install ADB on the Mac

If the terminal shows `zsh: command not found: adb`, install Android Platform Tools:

```bash
brew install android-platform-tools
```

Verify the installation:

```bash
adb version
```

If Android Studio is already installed, you can also use its bundled ADB directly:

```bash
~/Library/Android/sdk/platform-tools/adb version
```

## Step 2 — Connect the Glasses with the dev cable

1. Connect the Rokid dev cable to the data contacts on the left temple.
2. Connect the USB end to the Mac.
3. If the Glasses display an **Allow USB debugging** prompt, accept it.

Check the connection:

```bash
adb devices
```

Expected output:

```text
List of devices attached
XXXXXXXXXXXXXXXX    device
```

If the device status is `device`, ADB and USB debugging are working correctly.

If the device status is `unauthorized`, accept the USB debugging prompt on the Glasses.

If the list is empty, check that you are using the dev cable rather than a charging-only cable, then restart ADB:

```bash
adb kill-server
adb start-server
adb devices
```

## Step 3 — Install scrcpy and mirror the Glasses display

Install scrcpy:

```bash
brew install scrcpy
```

Start screen mirroring:

```bash
scrcpy
```

Optional commands:

```bash
# Mirror without controlling the Glasses from the Mac
scrcpy --window-title "Audit C — Rokid Glasses View" --no-control

# Mirror and record the demo
scrcpy --record demo.mp4
```

If the Glasses display appears on the Mac, the USB development connection is ready. You can install an APK even if Wi-Fi is not configured.

## Step 4 — Check the Wi-Fi status

Run:

```bash
adb shell ip addr show wlan0
```

If the output includes `state DOWN`, Wi-Fi is disabled or not connected.

If the output includes `<NO-CARRIER,...,UP>` and `state DOWN`, Wi-Fi is enabled but the Glasses are not connected to an access point.

Enable Wi-Fi and open the Android Wi-Fi settings screen:

```bash
adb shell svc wifi enable
adb shell am start -a android.settings.WIFI_SETTINGS
```

Use the mirrored screen to select the Wi-Fi network and enter its password. Then check the status again:

```bash
adb shell ip addr show wlan0
```

The connection is ready when an IP address appears, for example:

```text
inet 192.168.x.x/24
```

## Step 5 — Connect to the Mac backend over USB (optional)

If the Glasses only need to access a backend running on the Mac, Wi-Fi is not required. Forward the backend port over USB:

```bash
adb reverse tcp:8000 tcp:8000
```

The app on the Glasses can then access the Mac backend at:

```text
http://127.0.0.1:8000
```

## Step 6 — Install and launch the APK manually (optional)

From the Android project directory:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.auditc.glasses/.MainActivity
```

To restart the app:

```bash
adb shell am force-stop com.auditc.glasses
adb shell am start -n com.auditc.glasses/.MainActivity
```
