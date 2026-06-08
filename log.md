# Audit C — Project Log

> Hackathon: TRAE SOLO Unbound @ Tokyo, May 30, 2026
> Track: AI Glasses (Rokid) — Productivity Enhancement

---

## What We Built

**Audit C** is a hands-free AI quality control system for lab testing. It combines:

- a FastAPI backend for verification, observation logging, TTS alerts, and PDF report generation
- a mobile web AR-style demo for Safari/iPhone
- a full Android Kotlin app scaffold for future Rokid AR glasses integration

The current demo flow is intentionally simple and reliable:

- if speech contains any negative keyword, it is treated as a problem
- otherwise, it is treated as verified

---

## What Exists Now

### Backend (`main.py`)

Implemented FastAPI endpoints:

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/verify-step` | POST | Checks spoken text for negative keywords and returns `pass` or `fail` |
| `/log-observation` | POST | Appends observation entries to `session_log.json` |
| `/flag-issue` | POST | Flags the latest or specified step in the session log |
| `/generate-report` | POST | Builds `pathguard_report.pdf` from session data |
| `/tts` | POST | Calls MiniMax TTS and returns audio |
| `/tts/speak` | POST | Calls MiniMax TTS and plays audio on the host machine |

### Verification Logic

`/verify-step` now uses a fixed negative-keyword rule instead of SOP matching.

- If spoken text contains a negative keyword → `result: "fail"`
- Otherwise → `result: "pass"`, `step_name: "Step verified"`, `matched_keyword: "positive"`

Negative keywords:

```text
contaminated, contamination, failed, failure, error, wrong,
incorrect, mismatch, expired, missing, abnormal, unclear,
turbid, leak, spill, broken
```

### Web Frontend (`index.html`)

Built a mobile-optimized AR-style single page app:

- pure black background with green monospace HUD styling
- header text: `Audit C | COVID-19 PCR QC`
- shows current SOP step
- mic button for speech input
- `LOG OBSERVATION` button
- `GENERATE REPORT` button
- green fullscreen overlay for pass: `✓ VERIFIED`
- red fullscreen overlay for fail: `⚠ WARNING — ISSUE DETECTED`

iPhone/Safari specific behavior:

- microphone permission is requested on load
- speech recognition is initialized on button press
- if speech recognition fails, typed prompt fallback is used
- log observation uses typed prompt fallback directly

### Report Generation

PDF report generation is implemented with `reportlab`.

- output file: `pathguard_report.pdf`
- source data: `session_log.json` and `sop.json`
- includes step results, timestamps, flagged deviations, and summary content

### MiniMax TTS

MiniMax TTS support is integrated.

- configured around `https://api.minimax.io/v1/t2a_v2`
- `speak_alert(text)` is called from `/verify-step` when API key is available
- `.env` support was prepared for `MINIMAX_API_KEY`

### Android App (`auditc-android/`)

A complete Android Kotlin app project scaffold was created for `com.auditc.glasses`.

Implemented:

- single activity app
- dark AR-style medical UI
- Android `SpeechRecognizer` draft flow
- OkHttp networking to the FastAPI backend
- log observation dialog
- generate report dialog
- manifest permissions for `INTERNET` and `RECORD_AUDIO`
- `android:usesCleartextTraffic="true"`

Rokid integration TODO markers are already in the Kotlin code for:

- Rokid Speech SDK
- Rokid AR overlay API
- Rokid CXR-M SDK startup connection

---

## Current Status

| Component | Status | Notes |
| --- | --- | --- |
| FastAPI backend | ✅ Working | Running on port `8000` |
| Verify step logic | ✅ Working | Negative keyword detection confirmed |
| Log observation | ✅ Working | Writes to `session_log.json`, flags negative observations |
| Generate report | ✅ Working | Produces `pathguard_report.pdf` |
| CORS | ✅ Working | All origins allowed |
| MiniMax TTS | ✅ Integrated | Requires `MINIMAX_API_KEY` |
| Web frontend | ✅ Working | Served successfully for iPhone testing |
| iPhone Safari flow | ✅ Usable | Speech fallback to prompt is implemented |
| Android app project | ✅ Created | Opens in Android Studio, needs Android SDK path to build locally |
| Rokid hardware integration | ⏳ Pending | Hardware SDK swap not done yet |

---

## How To Run

### Backend

```bash
cd /Users/bellz_um/Desktop/Unbound
pip3 install -r requirements.txt
python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### Web Frontend

```bash
cd /Users/bellz_um/Desktop/Unbound
python3 -m http.server 8001 --bind 0.0.0.0
```

### Open On iPhone Safari

```text
http://192.168.1.11:8001
```

Fallback if needed:

```text
http://192.168.1.11:8001/index.html
```

### Environment Variable

```bash
export MINIMAX_API_KEY=your_key_here
```

### Android Build

Open `auditc-android/` in Android Studio.

If building from terminal, add `local.properties` with your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

Then run:

```bash
cd /Users/bellz_um/Desktop/Unbound/auditc-android
./gradlew :app:assembleDebug
```

Note: Gradle wrapper is included and working. The remaining build dependency is a valid Android SDK path on the machine.

---

## Latest Notes

- The live backend is reachable on `:8000`
- The web demo has been tested through a local static server on `:8001`
- `step one done` returns pass
- negative phrases like `contamination detected` return fail
- Safari speech input can still vary by device/browser permissions, but typed fallback keeps the demo usable

---

## Next Steps

### Immediate Demo Tasks

- [ ] Test end-to-end flow again on iPhone Safari
- [ ] Confirm `LOG OBSERVATION` and `GENERATE REPORT` on the phone
- [ ] Open the generated PDF and review formatting

### Android / Rokid Tasks

- [ ] Open `auditc-android/` in Android Studio
- [ ] Set Android SDK path and build `assembleDebug`
- [ ] Replace `SpeechRecognizer` with Rokid Speech SDK
- [ ] Replace overlay views with Rokid AR overlay API
- [ ] Add Rokid CXR-M startup connection

### Product Polish

- [ ] Add specimen ID and technician input in the frontend/app
- [ ] Improve report branding and layout
- [ ] Replace demo step progression with real SOP/session state handling

---

## File Structure

```text
Unbound/
├── main.py
├── sop.json
├── session_log.json
├── pathguard_report.pdf
├── index.html
├── .env
├── requirements.txt
├── log.md
└── auditc-android/
    ├── settings.gradle
    ├── build.gradle
    ├── gradlew
    └── app/
```

---

## Key Technical Decisions

| Decision | Reason |
| --- | --- |
| Negative keyword verification instead of SOP keyword matching | More reliable and faster for demo use |
| Mobile web demo for iPhone Safari | Easiest way to demo without Rokid hardware |
| Prompt fallback for Safari input | Keeps demo working when speech APIs are unreliable |
| FastAPI bound to `0.0.0.0` | Allows access from phone on same Wi‑Fi |
| Static frontend via `python3 -m http.server` | Fastest zero-framework deployment |
| Android project scaffolded now | Ready for later Rokid SDK swap-in |

---

*Log maintained during build session. Continue by updating this file with new test results, IP changes, or Rokid integration progress.*
