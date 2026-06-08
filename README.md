# Audit C

> Hands-free AI Quality Control for Lab Testing

**Track:** AI Glasses (Rokid) — TRAE SOLO Hackathon @ Tokyo, May 30, 2026

---

## 1. Project Overview

Audit C is a voice-powered AR quality control system for diagnostic labs. It witnesses every step of a lab procedure in real time, replacing trust-based paper checklists with AI-verified proof of compliance.

Built for **Rokid AR glasses**, **TRAE**, **MiniMax TTS**, and a **Python FastAPI backend**.

### What is built right now

- The technician speaks each lab step aloud.
- The system verifies the spoken step and shows a green overlay for success or a red overlay for a warning.
- Every verified or flagged step is stored with a **UTC ISO 8601 timestamp**.
- The technician name is captured by **voice at app launch** on the Rokid glasses.
- The Rokid app is now **voice-only** for the main workflow, with no typing UI.
- Reports can be exported in **PDF** or **TXT** format.
- The report includes the technician name and step timestamps.
- Local speech-to-text now uses **Whisper** through the backend instead of Android `SpeechRecognizer`.

### The problem Audit C solves

In many labs, ISO-style quality control still depends on:

- a technician checking boxes on paper
- a supervisor countersigning later
- no real proof that the procedure was actually followed correctly

One wrong reagent or one missed step in a PCR workflow can affect many patient results before anyone notices.

### How Audit C works

1. The technician puts on the Rokid RV101 glasses.
2. On launch, the glasses ask for the technician name by voice.
3. The technician says each step aloud.
4. The backend verifies the spoken step.
5. The glasses show:
   - green overlay for verified
   - red overlay for warning or issue
6. Each step is logged with a timestamp.
7. The technician says `generate PDF report` or `generate TXT report`.
8. The backend generates the report on the Mac.

---

## 2. Tech Stack

| Layer | Technology |
| --- | --- |
| AR glasses interface | Rokid RV101 + CXR-M SDK |
| Native glasses app | Android Kotlin |
| Backend API | Python FastAPI |
| Frontend simulator | Mobile HTML/JS |
| Report generation | ReportLab PDF + plain-text TXT export |
| Text-to-speech | MiniMax TTS |
| Speech-to-text | Local Whisper (`openai-whisper`) via `/transcribe` |
| Voice transport for glasses | `AudioRecord` on device + backend transcription |
| USB device tunnel | `adb reverse tcp:8000 tcp:8000` |
| Verification logic | TRAE + rule-based step matching |

---

## 3. How To Run

This section is written for someone who has never done this before.

### Step 1 — Requirements

You need:

- a Mac with a Thunderbolt or USB4 port
- Rokid RV101 glasses
- an iPhone with the **Hi Rokid** app installed
- Android Studio installed on the Mac
- Python 3 installed on the Mac

Optional but helpful:

- a USB-C cable that supports both data and power
- a terminal app on macOS

### Step 2 — Enable ADB on the glasses

1. Open the **Hi Rokid** app on your iPhone.
2. Go to **Settings**.
3. Open **Developer**.
4. Turn on **Glasses ADB debugging**.

This allows your Mac to see the glasses as an Android device.

### Step 3 — Connect the glasses to the Mac

1. Plug the Rokid RV101 glasses into your Mac using USB-C.
2. Open Terminal on the Mac.
3. Run:

```bash
adb devices
```

4. You should see a device ID, for example:

```text
List of devices attached
1901092546044163	device
```

If you do not see a device, check the cable, reconnect the glasses, and make sure ADB debugging is enabled in the Hi Rokid app.

### Step 4 — Install Python dependencies

From Terminal, run:

```bash
pip3 install fastapi uvicorn reportlab requests openai-whisper --break-system-packages
```

This installs:

- FastAPI for the backend
- Uvicorn to run the backend
- ReportLab for PDF generation
- Requests for HTTP calls
- Whisper for local speech-to-text

Note: if file upload errors appear later, also install:

```bash
pip3 install python-multipart --break-system-packages
```

### Step 5 — Start the backend

In Terminal:

```bash
cd Rokid_auditC_v2
python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Leave this terminal window open. The glasses app depends on this backend.

### Step 6 — Set up the USB tunnel

Run this every time you reconnect the glasses:

```bash
adb -s YOUR_DEVICE_ID reverse tcp:8000 tcp:8000
```

Example:

```bash
adb -s 1901092546044163 reverse tcp:8000 tcp:8000
```

Why this matters:

- the glasses app talks to `http://127.0.0.1:8000`
- `adb reverse` forwards that request through USB to the FastAPI server running on your Mac

### Step 7 — Build and deploy the app to the glasses

Run these commands in Terminal. Replace `YOUR_USERNAME` and `YOUR_DEVICE_ID` with your real values.

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME='/Users/YOUR_USERNAME/Desktop/Rokid_auditC_v2/.android-sdk'
export ANDROID_SDK_ROOT='/Users/YOUR_USERNAME/Desktop/Rokid_auditC_v2/.android-sdk'
cd Rokid_auditC_v2/auditc-rokid-android
./gradlew assembleDebug
adb -s YOUR_DEVICE_ID install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_DEVICE_ID shell am start -n com.auditc.glasses/.MainActivity
```

What these commands do:

- set Java so Gradle can build the Android app
- point Gradle at the local Android SDK
- build the debug APK
- install the APK onto the glasses
- launch the app

### Step 8 — Use the app on the glasses

1. Put on the glasses.
2. The overlay says: `Say your name after the beep`.
3. Say your name.
4. The app repeats the recognized name and asks for confirmation.
5. Say `Yes` to confirm or `No` to retry.
6. After that, use the main voice flow:
   - say each lab step aloud
   - green overlay means verified
   - red overlay means warning or issue
7. To export a report, say:
   - `generate PDF report`
   - `generate TXT report`

### Where reports are saved

Right now, generated reports are:

- available from the backend at:
  - `/reports/pathguard_report.pdf`
  - `/reports/pathguard_report.txt`
- written on your Mac in the project folder as:
  - `pathguard_report.pdf`
  - `pathguard_report.txt`

Example download URLs if the backend is running locally:

- `http://127.0.0.1:8000/reports/pathguard_report.pdf`
- `http://127.0.0.1:8000/reports/pathguard_report.txt`

---

## 4. Setting Up On A New Mac/PC

This section is for a completely new machine that has never run Audit C before.

### Prerequisites to install

- Android Studio: [https://developer.android.com/studio](https://developer.android.com/studio)
- Python 3: [https://www.python.org/downloads](https://www.python.org/downloads)
- Homebrew for macOS:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

- ADB via Homebrew on Mac:

```bash
brew install android-platform-tools
```

- ADB via direct download on Windows:
  - [https://developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)

### Clone the project

```bash
git clone https://github.com/Bellzum/Rokid_auditC_v2.git
cd Rokid_auditC_v2
```

### Install Python dependencies

```bash
pip3 install fastapi uvicorn reportlab requests openai-whisper python-multipart --break-system-packages
```

### First time Android SDK setup

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Desktop/Rokid_auditC_v2/.android-sdk"
export ANDROID_SDK_ROOT="$HOME/Desktop/Rokid_auditC_v2/.android-sdk"
```

### Enable ADB on glasses

Do this one time only:

1. Open the Hi Rokid app on your iPhone.
2. Go to `Settings`.
3. Open `Developer`.
4. Turn on `Glasses ADB debugging`.

### Every time you start a new session

Run:

```bash
./run_auditc.sh
```

This script:

- starts the backend
- builds the Rokid Android app
- installs the APK to the glasses
- sets up the USB tunnel
- launches the app

### Windows differences

- replace `export` with `set` for environment variables
- replace `python3` with `python`
- replace `pip3` with `pip`
- add the `platform-tools` folder to the system `PATH`
- set `JAVA_HOME` to Android Studio's JBR folder inside `Program Files`

### Recommended first-time Mac workflow

After cloning, your normal first-time setup becomes:

```bash
git clone https://github.com/Bellzum/Rokid_auditC_v2.git
cd Rokid_auditC_v2
./setup.sh
./run_auditc.sh
```

---

## 5. Known Issues And Workarounds

### App goes to background

Workaround:

- force stop and relaunch with `adb`

```bash
adb -s YOUR_DEVICE_ID shell am force-stop com.auditc.glasses
adb -s YOUR_DEVICE_ID shell am start -n com.auditc.glasses/.MainActivity
```

### App freezes on RV101

Workaround:

- the app may freeze occasionally on RV101 due to hardware constraints
- it should auto-recover within 8 seconds
- if it does not recover, run:

```bash
adb -s 1901092546044163 shell am force-stop com.auditc.glasses && adb -s 1901092546044163 reverse tcp:8000 tcp:8000 && adb -s 1901092546044163 shell am start -n com.auditc.glasses/.MainActivity
```

### Name not heard

Workaround:

- make sure the backend is running
- make sure `adb reverse` is active
- re-run Step 6

```bash
adb -s YOUR_DEVICE_ID reverse tcp:8000 tcp:8000
```

### No report generated

Workaround:

- make sure the FastAPI backend is running
- make sure `adb reverse` is active
- try the export command again

### USB connection lost

Workaround:

- unplug and reconnect the USB-C cable
- run `adb devices`
- confirm the device appears again
- re-run the reverse tunnel command

```bash
adb devices
adb -s YOUR_DEVICE_ID reverse tcp:8000 tcp:8000
```

---

## 6. Next Steps / Roadmap

- Fix report generation from glasses if any remaining device-side edge cases appear during live use.
- Add equipment detection using the glasses camera plus a lightweight YOLO-based model.
- Update `PathGuardDemoActivity.kt` and `PathGuardRokidActivity.kt` to match the new voice-only flow.
- Add an offline mode so the app can continue working when the Mac backend is unavailable.

---

## API Endpoints

| Endpoint | Method | Description |
| --- | --- | --- |
| `/verify-step` | `POST` | Verify a spoken step and return pass/fail plus timestamp |
| `/log-observation` | `POST` | Log a spoken observation with timestamp |
| `/flag-issue` | `POST` | Flag the current step for supervisor review |
| `/generate-report` | `POST` | Generate a `pdf` or `txt` report |
| `/reports/{filename}` | `GET` | Download the generated PDF or TXT report |
| `/transcribe` | `POST` | Transcribe uploaded audio with local Whisper |
| `/tts/speak` | `POST` | Speak text through MiniMax TTS |

---

## Demo Flow

```text
1. Glasses ask for technician name by voice
2. Say "Aiko Tanaka"                        -> name capture
3. Say "Yes"                               -> confirm name
4. Say "step one done sample collected"    -> green verified overlay
5. Say "contamination detected"            -> red warning overlay
6. Say "generate PDF report"               -> PDF report generated
7. Say "generate TXT report"               -> TXT report generated
```

---

## 7. Team

- **Biomedical domain expert** — protocol design, QC validation, demo narrative
- Built with TRAE SOLO + Claude Sonnet
- Debugged and extended with TRAE Solo Agent

---

## License

MIT — Built at TRAE SOLO Hackathon 2026
