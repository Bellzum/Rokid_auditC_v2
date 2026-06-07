# Audit C 🔬

&#x20;

> Hands-free AI Quality Control for Lab Testing

**Track:** AI Glasses (Rokid) — TRAE SOLO Hackathon @ Tokyo, May 30, 2026

***

## What is Audit C?

Audit C is a voice-powered AR quality control system for diagnostic labs. It witnesses every step of a lab procedure in real-time — replacing trust-based paper checklists with AI-verified, timestamped proof of compliance.

Built for **Rokid AR Glasses** + **TRAE** + **MiniMax TTS**.

***

## The Problem

In most hospital labs today, ISO 15189 quality control relies on:

- A technician signing a paper checklist ✍️
- A supervisor countersigning ✍️
- **No proof the steps were actually done correctly** ❌

One wrong reagent in a PCR run = false negatives for hundreds of patients. Nobody catches it until it's too late.

***

## The Solution

Audit C uses **voice + AR display** to witness every step:

1. Technician speaks each step aloud
2. AI verifies — green if correct, red alert if deviation detected
3. Every step auto-logged with timestamp
4. One voice command generates a full ISO compliance report

***

## Tech Stack

Layer

Technology

AR glasses interface

Rokid Glasses + CXR-M SDK

Voice alerts

MiniMax Speech-2.6-turbo

AI verification

TRAE + LLM

Backend

Python FastAPI

Frontend

Mobile HTML/JS (AR simulation)

Report generation

ReportLab PDF

***

## Quick Start

```
# Install dependencies
pip3 install fastapi uvicorn reportlab requests --break-system-packages

# Start backend
python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# Start frontend (new terminal)
python3 -m http.server 3000 --bind $(ipconfig getifaddr en0)

# Open on mobile
http://YOUR_IP:3000/index.html

```

***

## API Endpoints

Endpoint

Method

Description

`/verify-step`

POST

Verify spoken step — returns pass/fail

`/log-observation`

POST

Log a voice observation with timestamp

`/flag-issue`

POST

Flag current step for supervisor review

`/generate-report`

POST

Generate ISO-style PDF audit report

`/tts/speak`

POST

Speak text via MiniMax TTS

***

## Demo Flow

```
1. Say "step one done, sample collected"  → ✅ GREEN — VERIFIED
2. Say "contamination detected"           → 🔴 RED — WARNING
3. Say "step two done, reagent added"     → ✅ GREEN — VERIFIED
4. Tap "Generate Report"                  → 📄 PDF audit trail generated

```

***

## Team

- **Biomedical domain expert** — protocol design, QC validation, demo narrative
- Built with TRAE SOLO + Claude Sonnet

***

## License

MIT — Built at TRAE SOLO Hackathon 2026
# Rokid_auditC_v2
