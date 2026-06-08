# PathGuard — TRAE SOLO Hackathon Notes
> Saved: May 29, 2026 | Event: May 30, 2026 (12:30–20:30) | Venue: Shinbashi, Tokyo

---

## Hackathon Details

- **Event:** Unbound Creativity with TRAE SOLO @ Japan
- **Track:** AI Glasses (co-hosted by Rokid)
- **Team size:** Max 3 members (you are currently solo — looking for teammates)
- **Discord:** https://discord.gg/uYuwRJZX (find teammates here tonight)

### AI Glasses Track Judging Criteria
| Criterion | Weight |
|---|---|
| Rokid glasses form-factor fit | 40% |
| TRAE depth & development efficiency | 30% |
| Innovation & interaction design | 20% |
| Completeness & integration | 10% |

### Prizes (AI Glasses Track)
- 1st place: JPY 50,000
- 2nd place: JPY 25,000
- 3rd place: JPY 20,000
- Rokid Rising Builder Awards (up to 5 teams): total JPY 25,000

---

## Your Background
- Biomedical research
- Domain expertise: histopathology, clinical lab, PCR testing, ISO 15189 compliance
- Role in team: domain expert, QA tester, presenter (the medical problem story)

---

## Project Concept: PathGuard

### One-line pitch
> AI-powered hands-free chain-of-custody system for COVID PCR lab testing — Rokid Glasses witness every step, replacing trust-based QC with AI-witnessed proof.

### The Problem
In most hospital diagnostic labs today, ISO 15189 QC relies on a technician signing a checklist and a supervisor countersigning. There is **no proof** that each step was actually performed correctly. One wrong reagent in a PCR run could produce false negatives for hundreds of patients — and nobody catches it until it is too late.

### The Solution
Rokid Glasses + TRAE-powered AI assistant that:
- Scans barcodes on specimens, reagents, and machines at each step
- Verifies each scan against the SOP (Standard Operating Procedure) in real-time
- Fires an immediate voice + AR alert if the wrong item is detected
- Auto-logs every step with timestamp, technician ID, and pass/fail status
- Generates a full ISO 15189-ready audit report on voice command

### Why It Must Be Glasses
The lab technician's hands are always occupied with specimens, pipettes, and tubes. A phone or screen is physically impossible to use in a sterile workflow. This is the textbook use case for wearable AI.

---

## COVID PCR Demo Workflow (5 steps)

| Step | Action | Glasses detect | Normal result | Alert condition |
|---|---|---|---|---|
| 1 | Specimen intake | Specimen barcode | Patient ID logged | Barcode mismatch |
| 2 | Reagent aliquot | Reagent vial barcode | Correct reagent confirmed | Wrong reagent → RED ALERT |
| 3 | Transfer to tube | Tube barcode | Tube matches specimen | ID mismatch |
| 4 | Load PCR machine | Machine ID + slot | Run start logged | Wrong machine/slot |
| 5 | Report generation | Voice command | ISO 15189 report output | Flagged deviations highlighted |

### Demo Strategy (8 hours = build steps 1, 2, 5 — describe 3 & 4 verbally)
The three built steps cover the most dramatic moments:
- Step 1: instant specimen verification (visual wow)
- Step 2: wrong reagent alert (the emotional peak of the demo)
- Step 5: one voice command generates the full audit report (the payoff)

---

## 8-Hour Build Plan

### Phase 1 — Setup & alignment (12:00–13:30, 1.5 hrs)
- Install TRAE SOLO + Rokid SDK, verify glasses connection
- Print 3 paper barcode labels: specimen ID, reagent vial (correct), reagent vial (wrong)
- **You:** write the COVID PCR SOP as a simple JSON (5 steps, expected barcode per step) — ~20 min
- Dev: set up project repo and TRAE workspace
- Designer: wireframe AR overlay UI

### Phase 2 — Core feature 1: barcode scan + verification (13:30–15:30, 2 hrs)
- Rokid camera reads QR/barcode
- TRAE matches against SOP JSON step by step
- On match: green AR checkmark + voice "Specimen ID verified"
- On mismatch: red AR alert + voice "Wrong reagent — expected Reagent A"
- Every scan auto-logs: barcode value, timestamp, step number, pass/fail
- **You:** QA test each scenario with the paper labels

### Phase 3 — Core feature 2: voice logging (15:30–16:30, 1 hr)
- Technician speaks observation → AI transcribes + appends to log with timestamp
- Voice "next step" advances SOP checklist on AR overlay
- Voice "flag issue" marks step for supervisor review
- Use Rokid's built-in voice API (no custom wake word needed for demo)

### Phase 4 — Core feature 3: auto-report generation (16:30–17:30, 1 hr)
- Voice "generate report" → TRAE compiles session log into structured report
- Report includes: specimen ID, technician ID, each step + timestamp + pass/fail, deviations, observations
- Output as PDF or on-screen display
- **You:** validate report format against ISO 15189 fields

### Phase 5 — Polish & rehearsal (17:30–18:10, 40 min)
- Run full demo end-to-end twice
- Prepare paper barcode labels neatly (your props — make them look credible)
- Prepare the intentional "wrong reagent" failure scenario
- Agree on who speaks what: you = medical problem + impact, dev = live tech demo

### Phase 6 — Presentation (18:10–19:10)
**Slide structure:**
1. The problem — "Today PCR lab QC relies on trust. One wrong reagent = false negatives for hundreds of patients."
2. The solution — PathGuard witnesses every step (show workflow diagram)
3. Live demo — correct specimen scan (green) → wrong reagent scan (red alert) → generate report
4. How we used TRAE — list 4 TRAE use cases explicitly for judges
5. Real-world impact + next steps

---

## How TRAE Is Used (for judges slide)
1. **SOP generation** — TRAE generates the protocol JSON from a plain-text description
2. **Verification logic** — TRAE powers the barcode-to-SOP matching and deviation detection
3. **Voice transcription & logging** — TRAE structures spoken observations into formatted log entries
4. **Report generation** — TRAE compiles the full ISO 15189-ready audit report from session data

---

## Judging Score Estimate
| Criterion | Weight | PathGuard score | Notes |
|---|---|---|---|
| Glasses form-factor fit | 40% | 38/40 | Hands-occupied lab = perfect fit |
| TRAE depth | 30% | 26/30 | 4 distinct TRAE use cases |
| Innovation & interaction | 20% | 17/20 | Genuinely new — no existing product solves this |
| Completeness | 10% | 8/10 | 3 working features is enough per organizer criteria |
| **Total** | | **89/100** | |

---

## Teammate Recruitment (post in Discord tonight)

> **Looking for teammates for PathGuard — AI Glasses Track**
>
> I'm a biomedical researcher building PathGuard: an AI-powered hands-free QC system for COVID PCR labs using Rokid Glasses + TRAE. The glasses verify each step of a PCR test against the SOP in real-time — replacing trust-based compliance with AI-witnessed proof.
>
> Looking for:
> - **1 developer** (React/Python, Rokid SDK integration, TRAE workflows)
> - **1 designer** (AR overlay UX, demo presentation)
>
> Strong domain knowledge on the medical problem side. Clear build plan for 8 hours. DM me!

---

## Props to Prepare Before the Event
- [ ] 3 printed barcode/QR labels: "SPECIMEN-001", "REAGENT-A-CORRECT", "REAGENT-B-WRONG"
- [ ] Simple SOP JSON file written in advance (ask Claude to help generate this)
- [ ] Know your ISO 15189 fields for the report: date, specimen ID, technician ID, test method, each step result, deviations, authorised by

---

## Demo Script (60 seconds)

> "In most hospitals today, PCR lab QC relies on a signature. PathGuard makes every step witnessed by AI.
>
> Watch — the technician picks up the specimen tube. The glasses scan it. Specimen ID verified. Green.
>
> Now the reagent vial. Glasses scan it. Wrong reagent — expected Reagent A, got Reagent B. Red alert fires instantly.
>
> In the current system, this mistake would be invisible. Here, it is caught before a single drop is pipetted.
>
> At the end of the run — one voice command: 'generate report.' A complete ISO 15189 audit trail, every step timestamped, every deviation flagged, ready for supervisor sign-off.
>
> No paper. No trust. Just proof."

---

## Next Steps for Tomorrow
- [ ] Find teammates on Discord tonight
- [ ] Generate SOP JSON with Claude (ask: "Help me write a COVID PCR SOP as a 5-step JSON for PathGuard")
- [ ] Print barcode labels before leaving home
- [ ] Review Rokid SDK docs: https://discord.gg/uYuwRJZX (shared in Discord)
- [ ] Arrive by 12:00 for check-in and team formation

---

*Generated with Claude — continue brainstorming by sharing this file in a new chat*
