from contextlib import asynccontextmanager
from fastapi import BackgroundTasks, FastAPI, HTTPException, Request, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from datetime import datetime, timezone
import json
import os
import requests
import subprocess
import tempfile
from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

MINIMAX_API_KEY = os.getenv("MINIMAX_API_KEY")
MINIMAX_TTS_URL = "https://api.minimax.io/v1/t2a_v2"
MINIMAX_TEXT_URL = "https://api.minimax.io/v1/text/chatcompletion_v2"
WHISPER_MODEL_NAME = os.getenv("WHISPER_MODEL_NAME", "tiny")
SESSION_LOG_PATH = os.path.join(os.path.dirname(__file__), "session_log.json")
REPORT_PDF_PATH = os.path.join(os.path.dirname(__file__), "pathguard_report.pdf")
REPORT_TXT_PATH = os.path.join(os.path.dirname(__file__), "pathguard_report.txt")
TEST_NAME = "COVID-19 PCR"
NEGATIVE_KEYWORDS = [
    "contaminated",
    "contamination",
    "failed",
    "failure",
    "error",
    "wrong",
    "incorrect",
    "mismatch",
    "expired",
    "missing",
    "abnormal",
    "unclear",
    "turbid",
    "leak",
    "spill",
    "broken",
]
_WHISPER_MODEL = None


class VerifyRequest(BaseModel):
    spoken_text: str
    step_number: int | None = None
    user_name: str | None = None


class VerifyResponse(BaseModel):
    result: str
    step_name: str | None
    timestamp: str
    matched_keyword: str | None


class TTSRequest(BaseModel):
    text: str
    voice_id: str = "English_Graceful_Lady"
    speed: float = 1.0
    model: str = "speech-2.8-hd"


class LogObservationRequest(BaseModel):
    spoken_text: str
    step_number: int
    user_name: str | None = None


class FlagIssueRequest(BaseModel):
    step_number: int | None = None


class GenerateReportRequest(BaseModel):
    specimen_id: str = "UNKNOWN"
    technician_name: str = "UNKNOWN"
    user_name: str | None = None
    format: str = "pdf"


def load_sop_data():
    sop_path = os.path.join(os.path.dirname(__file__), "sop.json")
    with open(sop_path, "r") as f:
        return json.load(f)


def print_sop_data_on_startup() -> None:
    sop_data = load_sop_data()
    print("Loaded sop.json:")
    print(json.dumps(sop_data, indent=2))


def get_whisper_model():
    global _WHISPER_MODEL
    if _WHISPER_MODEL is not None:
        return _WHISPER_MODEL

    try:
        import whisper
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="openai-whisper is not installed") from exc

    try:
        _WHISPER_MODEL = whisper.load_model(WHISPER_MODEL_NAME)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Failed to load Whisper model: {exc}") from exc

    return _WHISPER_MODEL


def transcribe_audio_file(audio_path: str) -> str:
    model = get_whisper_model()
    try:
        result = model.transcribe(audio_path, language="en", fp16=False)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Whisper transcription failed: {exc}") from exc

    transcript = (result.get("text") or "").strip()
    if not transcript:
        raise HTTPException(status_code=422, detail="No speech recognized")
    return transcript


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        print_sop_data_on_startup()
    except FileNotFoundError:
        print("sop.json not found at startup")
    except json.JSONDecodeError:
        print("Invalid JSON in sop.json at startup")
    yield


app = FastAPI(lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_user_name(user_name: str | None) -> str | None:
    if user_name is None:
        return None
    normalized = user_name.strip()
    return normalized or None


def load_session_state() -> dict:
    if not os.path.exists(SESSION_LOG_PATH):
        return {"user_name": None, "entries": []}

    with open(SESSION_LOG_PATH, "r") as f:
        data = json.load(f)

    if isinstance(data, list):
        return {"user_name": None, "entries": data}

    if not isinstance(data, dict):
        raise HTTPException(status_code=500, detail="Invalid JSON in session_log.json")

    entries = data.get("entries", [])
    if not isinstance(entries, list):
        raise HTTPException(status_code=500, detail="Invalid session entries in session_log.json")

    return {
        "user_name": normalize_user_name(data.get("user_name")),
        "entries": entries,
    }


def load_session_log() -> list[dict]:
    return load_session_state()["entries"]


def save_session_state(session_state: dict) -> None:
    with open(SESSION_LOG_PATH, "w") as f:
        json.dump(session_state, f, indent=2)


def save_session_log(entries: list[dict], user_name: str | None = None) -> None:
    save_session_state({"user_name": normalize_user_name(user_name), "entries": entries})


def update_session_user_name(session_state: dict, user_name: str | None) -> None:
    normalized = normalize_user_name(user_name)
    if normalized:
        session_state["user_name"] = normalized


def step_number_aliases(step_number: int) -> list[str]:
    number_words = {
        1: "one",
        2: "two",
        3: "three",
        4: "four",
        5: "five",
        6: "six",
        7: "seven",
        8: "eight",
        9: "nine",
        10: "ten",
    }
    aliases = [f"step {step_number}"]
    if step_number in number_words:
        aliases.append(f"step {number_words[step_number]}")
    return aliases


def find_negative_keyword(text: str) -> str | None:
    text_lower = text.lower()
    return next((keyword for keyword in NEGATIVE_KEYWORDS if keyword in text_lower), None)


def match_step_by_text(text: str, sop_steps: list[dict]) -> tuple[int | None, str | None]:
    normalized_text = text.lower()
    best_index = None
    best_score = 0

    for index, step in enumerate(sop_steps, start=1):
        terms = set(step.get("voice_keywords", []))
        terms.update(step_number_aliases(index))
        step_name = step.get("step_name", "")
        if step_name:
            terms.add(step_name.lower())

        score = sum(1 for term in terms if term and term.lower() in normalized_text)
        if score > best_score:
            best_index = index
            best_score = score

    if best_index is None or best_score == 0:
        return None, None

    return best_index, sop_steps[best_index - 1].get("step_name", f"Step {best_index}")


def resolve_step_context(
    spoken_text: str,
    requested_step_number: int | None,
    sop_steps: list[dict],
) -> tuple[int | None, str | None]:
    if requested_step_number and 1 <= requested_step_number <= len(sop_steps):
        step_name = sop_steps[requested_step_number - 1].get(
            "step_name", f"Step {requested_step_number}"
        )
        return requested_step_number, step_name

    return match_step_by_text(spoken_text, sop_steps)


def append_session_entry(
    session_state: dict,
    *,
    step_number: int | None,
    step_name: str | None,
    result: str | None,
    observation: str | None,
    flagged: bool,
    user_name: str | None = None,
    detected_equipment: list[dict] | None = None,
) -> dict:
    update_session_user_name(session_state, user_name)
    entry = {
        "step_number": step_number,
        "step_name": step_name,
        "result": result,
        "observation": observation,
        "timestamp": utc_now_iso(),
        "flagged": flagged,
        "user_name": session_state.get("user_name"),
    }
    if detected_equipment:
        entry["detected_equipment"] = detected_equipment
    session_state["entries"].append(entry)
    return entry


def compile_step_results(sop_data: dict, session_entries: list[dict]) -> list[dict]:
    step_results = []
    for index, step in enumerate(sop_data.get("steps", []), start=1):
        matching_entries = [
            entry for entry in session_entries if entry.get("step_number") == index
        ]
        latest_entry = matching_entries[-1] if matching_entries else None
        flagged = any(entry.get("flagged", False) for entry in matching_entries)
        latest_result_entry = next(
            (entry for entry in reversed(matching_entries) if entry.get("result") in {"pass", "fail"}),
            None,
        )
        latest_observation_entry = next(
            (entry for entry in reversed(matching_entries) if entry.get("observation")),
            None,
        )

        if latest_entry:
            timestamp_source = latest_result_entry or latest_entry
            timestamp = timestamp_source.get("timestamp", "Not recorded")
            observation_source = latest_observation_entry or latest_entry
            observation = observation_source.get("observation") or "No observation recorded."
            if flagged:
                status = "fail"
            elif latest_result_entry:
                status = latest_result_entry.get("result", "fail")
            else:
                status = "fail"
        else:
            timestamp = "Not recorded"
            observation = "No observation recorded."
            status = "fail"

        step_results.append(
            {
                "step_number": index,
                "step_name": step.get("step_name", f"Step {index}"),
                "timestamp": timestamp,
                "status": status,
                "flagged": flagged,
                "observation": observation,
            }
        )

    return step_results


def contains_negative(text: str) -> bool:
    return find_negative_keyword(text) is not None


def text_to_speech(
    text: str,
    voice_id: str = "English_Graceful_Lady",
    speed: float = 1.0,
    model: str = "speech-2.8-hd",
    api_key: str | None = None,
) -> tuple[bytes, dict]:
    token = api_key or MINIMAX_API_KEY
    if not token:
        raise ValueError("MINIMAX_API_KEY must be set")

    payload = {
        "model": model,
        "text": text,
        "stream": False,
        "output_format": "hex",
        "language_boost": "auto",
        "voice_setting": {
            "voice_id": voice_id,
            "speed": speed,
            "vol": 1.0,
            "pitch": 0
        },
        "audio_setting": {
            "sample_rate": 32000,
            "bitrate": 128000,
            "format": "mp3",
            "channel": 1
        }
    }

    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    response = requests.post(MINIMAX_TTS_URL, json=payload, headers=headers, timeout=60)

    if response.status_code != 200:
        raise HTTPException(status_code=response.status_code, detail=f"MiniMax API error: {response.text}")

    result = response.json()

    if result.get("base_resp", {}).get("status_code") != 0:
        raise HTTPException(
            status_code=500,
            detail=result.get("base_resp", {}).get("status_msg", "MiniMax API error"),
        )

    audio_hex = result.get("data", {}).get("audio")
    if not audio_hex:
        raise HTTPException(status_code=500, detail="MiniMax API returned no audio")

    return bytes.fromhex(audio_hex), result


def compile_report_sections(
    specimen_id: str,
    technician_name: str,
    user_name: str,
    step_results: list[dict],
) -> dict:
    if not MINIMAX_API_KEY:
        flagged = [step for step in step_results if step["flagged"]]
        passed = [step for step in step_results if step["status"] == "pass"]
        return {
            "overview": (
                f"Report for user {user_name}, specimen {specimen_id}, prepared by technician "
                f"{technician_name} for {TEST_NAME}."
            ),
            "summary": (
                f"{len(passed)} of {len(step_results)} SOP steps have pass status based on the session log. "
                "Any missing or flagged steps are marked as fail."
            ),
            "flagged_deviations": [
                f"Step {step['step_number']} - {step['step_name']}: {step['observation']}"
                for step in flagged
            ],
            "final_assessment": (
                "Manual review required." if flagged else "No flagged deviations recorded."
            ),
        }

    prompt_payload = {
        "specimen_id": specimen_id,
        "technician_name": technician_name,
        "user_name": user_name,
        "test_name": TEST_NAME,
        "step_results": step_results,
    }
    response_format = {
        "type": "json_schema",
        "json_schema": {
            "name": "pathguard_report",
            "description": "Structured PathGuard laboratory report content.",
            "schema": {
                "type": "object",
                "properties": {
                    "overview": {"type": "string"},
                    "summary": {"type": "string"},
                    "flagged_deviations": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "final_assessment": {"type": "string"},
                },
                "required": [
                    "overview",
                    "summary",
                    "flagged_deviations",
                    "final_assessment",
                ],
            },
        },
    }
    payload = {
        "model": "MiniMax-Text-01",
        "temperature": 0.3,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are a laboratory compliance reporting assistant. "
                    "Write concise, factual report sections for a COVID-19 PCR SOP execution report. "
                    "Do not invent data. Missing steps should be treated as incomplete."
                ),
            },
            {
                "role": "user",
                "content": (
                    "Create structured report sections for the following execution data. "
                    "Include a short overview, a summary of overall SOP adherence, a list of flagged deviations, "
                    "and a final assessment.\n\n"
                    f"{json.dumps(prompt_payload, indent=2)}"
                ),
            },
        ],
        "response_format": response_format,
    }
    headers = {
        "Authorization": f"Bearer {MINIMAX_API_KEY}",
        "Content-Type": "application/json",
    }
    response = requests.post(MINIMAX_TEXT_URL, json=payload, headers=headers, timeout=60)

    if response.status_code != 200:
        raise HTTPException(status_code=response.status_code, detail=f"MiniMax API error: {response.text}")

    result = response.json()
    if result.get("base_resp", {}).get("status_code") not in (None, 0):
        raise HTTPException(
            status_code=500,
            detail=result.get("base_resp", {}).get("status_msg", "MiniMax text API error"),
        )

    content = result.get("choices", [{}])[0].get("message", {}).get("content")
    if not content:
        raise HTTPException(status_code=500, detail="MiniMax text API returned no report content")

    if isinstance(content, str):
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            return {
                "overview": content,
                "summary": content,
                "flagged_deviations": [],
                "final_assessment": content,
            }

    return content


def generate_pdf_report(
    specimen_id: str,
    technician_name: str,
    user_name: str,
    step_results: list[dict],
    report_sections: dict,
) -> None:
    doc = SimpleDocTemplate(
        REPORT_PDF_PATH,
        pagesize=letter,
        leftMargin=0.6 * inch,
        rightMargin=0.6 * inch,
        topMargin=0.6 * inch,
        bottomMargin=0.6 * inch,
    )
    styles = getSampleStyleSheet()
    flagged_style = ParagraphStyle(
        "FlaggedText",
        parent=styles["BodyText"],
        textColor=colors.red,
    )

    story = [
        Paragraph("PathGuard PCR Execution Report", styles["Title"]),
        Spacer(1, 0.2 * inch),
    ]

    metadata_table = Table(
        [
            ["User Name", user_name],
            ["Specimen ID", specimen_id],
            ["Technician Name", technician_name],
            ["Test Name", TEST_NAME],
            ["Generated At (UTC)", utc_now_iso()],
        ],
        colWidths=[1.8 * inch, 4.7 * inch],
    )
    metadata_table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (0, -1), colors.lightgrey),
                ("BOX", (0, 0), (-1, -1), 0.5, colors.black),
                ("INNERGRID", (0, 0), (-1, -1), 0.25, colors.grey),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ]
        )
    )
    story.extend([metadata_table, Spacer(1, 0.25 * inch)])

    story.append(Paragraph("Step Execution", styles["Heading2"]))
    table_rows = [[
        "Step #",
        "Step Name",
        "Timestamp",
        "Status",
        "Observation",
    ]]
    for step in step_results:
        status_color = "green" if step["status"] == "pass" else "red"
        status_cell = Paragraph(
            f'<font color="{status_color}">{step["status"].upper()}</font>',
            styles["BodyText"],
        )
        observation_style = flagged_style if step["flagged"] else styles["BodyText"]
        table_rows.append(
            [
                str(step["step_number"]),
                Paragraph(step["step_name"], styles["BodyText"]),
                Paragraph(step["timestamp"], styles["BodyText"]),
                status_cell,
                Paragraph(step["observation"], observation_style),
            ]
        )

    step_table = Table(
        table_rows,
        colWidths=[0.6 * inch, 1.8 * inch, 1.7 * inch, 0.8 * inch, 2.3 * inch],
        repeatRows=1,
    )
    step_table_style = [
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1f4e79")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BOX", (0, 0), (-1, -1), 0.5, colors.black),
        ("INNERGRID", (0, 0), (-1, -1), 0.25, colors.grey),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.whitesmoke, colors.lightyellow]),
    ]
    for row_index, step in enumerate(step_results, start=1):
        if step["flagged"]:
            step_table_style.append(
                ("BACKGROUND", (0, row_index), (-1, row_index), colors.HexColor("#ffd9d9"))
            )
    step_table.setStyle(TableStyle(step_table_style))
    story.extend([step_table, Spacer(1, 0.25 * inch)])

    story.append(Paragraph("Overview", styles["Heading2"]))
    story.extend([Paragraph(report_sections["overview"], styles["BodyText"]), Spacer(1, 0.15 * inch)])

    story.append(Paragraph("Flagged Deviations", styles["Heading2"]))
    flagged_deviations = report_sections.get("flagged_deviations", [])
    if flagged_deviations:
        for item in flagged_deviations:
            story.append(Paragraph(f"- {item}", flagged_style))
    else:
        story.append(Paragraph("No flagged deviations were reported by the LLM summary.", styles["BodyText"]))
    story.append(Spacer(1, 0.15 * inch))

    story.append(Paragraph("Summary", styles["Heading2"]))
    story.extend([Paragraph(report_sections["summary"], styles["BodyText"]), Spacer(1, 0.15 * inch)])

    story.append(Paragraph("Final Assessment", styles["Heading2"]))
    story.append(Paragraph(report_sections["final_assessment"], styles["BodyText"]))

    doc.build(story)


def generate_txt_report(
    specimen_id: str,
    technician_name: str,
    user_name: str,
    step_results: list[dict],
    report_sections: dict,
) -> None:
    lines = [
        "PathGuard PCR Execution Report",
        "=" * 30,
        f"User Name: {user_name}",
        f"Specimen ID: {specimen_id}",
        f"Technician Name: {technician_name}",
        f"Test Name: {TEST_NAME}",
        f"Generated At (UTC): {utc_now_iso()}",
        "",
        "Step Execution",
        "-" * 14,
    ]

    for step in step_results:
        lines.extend(
            [
                f"Step {step['step_number']}: {step['step_name']}",
                f"Timestamp: {step['timestamp']}",
                f"Status: {step['status'].upper()}",
                f"Observation: {step['observation']}",
                f"Flagged: {'Yes' if step['flagged'] else 'No'}",
                "",
            ]
        )

    lines.extend(
        [
            "Overview",
            "-" * 8,
            report_sections["overview"],
            "",
            "Flagged Deviations",
            "-" * 18,
        ]
    )

    flagged_deviations = report_sections.get("flagged_deviations", [])
    if flagged_deviations:
        lines.extend(f"- {item}" for item in flagged_deviations)
    else:
        lines.append("No flagged deviations were reported by the LLM summary.")

    lines.extend(
        [
            "",
            "Summary",
            "-" * 7,
            report_sections["summary"],
            "",
            "Final Assessment",
            "-" * 16,
            report_sections["final_assessment"],
            "",
        ]
    )

    with open(REPORT_TXT_PATH, "w") as f:
        f.write("\n".join(lines))


def play_audio(audio_bytes: bytes):
    with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as temp_file:
        temp_file.write(audio_bytes)
        temp_file_path = temp_file.name

    try:
        if os.name == "posix":
            if os.path.exists("/usr/bin/afplay"):
                subprocess.run(["/usr/bin/afplay", temp_file_path], check=True)
            else:
                subprocess.run(["mpg123", "-q", temp_file_path], check=True)
        elif os.name == "nt":
            os.startfile(temp_file_path)
    finally:
        os.unlink(temp_file_path)


def speak_alert(text: str) -> None:
    audio_bytes, _ = text_to_speech(
        text=text,
        voice_id="female-shaonv",
        model="speech-2.6-turbo",
    )
    play_audio(audio_bytes)


@app.post("/verify-step", response_model=VerifyResponse)
async def verify_step(request: VerifyRequest, background_tasks: BackgroundTasks):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state()
    except FileNotFoundError:
        raise HTTPException(status_code=500, detail="Required verification source file not found")
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in verification source file")

    step_number, step_name = resolve_step_context(
        request.spoken_text,
        request.step_number,
        sop_data.get("steps", []),
    )
    matched_keyword = find_negative_keyword(request.spoken_text)
    timestamp = utc_now_iso()

    if matched_keyword:
        append_session_entry(
            session_state,
            step_number=step_number,
            step_name=step_name,
            result="fail",
            observation=request.spoken_text,
            flagged=True,
            user_name=request.user_name,
        )
        save_session_state(session_state)
        response = VerifyResponse(
            result="fail",
            step_name=step_name,
            timestamp=timestamp,
            matched_keyword=matched_keyword,
        )
        if MINIMAX_API_KEY:
            background_tasks.add_task(speak_alert, "Warning. Issue detected.")
        return response

    append_session_entry(
        session_state,
        step_number=step_number,
        step_name=step_name,
        result="pass",
        observation=request.spoken_text,
        flagged=False,
        user_name=request.user_name,
    )
    save_session_state(session_state)
    response = VerifyResponse(
        result="pass",
        step_name=step_name or "Step verified",
        timestamp=timestamp,
        matched_keyword="positive",
    )
    if MINIMAX_API_KEY:
        background_tasks.add_task(speak_alert, "Verified.")
    return response


@app.post("/transcribe")
async def transcribe_audio(
    request: Request,
    audio: UploadFile | None = File(default=None),
):
    if audio is not None:
        audio_bytes = await audio.read()
        suffix = os.path.splitext(audio.filename or "")[1] or ".wav"
    else:
        audio_bytes = await request.body()
        suffix = ".wav"

    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Audio body is empty")

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
            temp_file.write(audio_bytes)
            temp_path = temp_file.name

        transcript = transcribe_audio_file(temp_path)
        return {"text": transcript, "transcript": transcript}
    finally:
        if temp_path and os.path.exists(temp_path):
            os.unlink(temp_path)


@app.post("/tts")
async def tts_endpoint(request: TTSRequest):
    try:
        audio_bytes, result = text_to_speech(
            text=request.text,
            voice_id=request.voice_id,
            speed=request.speed,
            model=request.model
        )
        return {
            "status": "success",
            "audio_hex": audio_bytes.hex(),
            "voice_id": request.voice_id,
            "model": request.model,
            "trace_id": result.get("trace_id"),
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tts/speak")
async def tts_speak_endpoint(request: TTSRequest):
    try:
        audio_bytes, result = text_to_speech(
            text=request.text,
            voice_id=request.voice_id,
            speed=request.speed,
            model=request.model
        )
        play_audio(audio_bytes)
        return {
            "status": "success",
            "text": request.text,
            "voice_id": request.voice_id,
            "model": request.model,
            "trace_id": result.get("trace_id"),
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/log-observation")
async def log_observation(request: LogObservationRequest):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state()
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in session_log.json")

    step_name = None
    steps = sop_data.get("steps", [])
    if 1 <= request.step_number <= len(steps):
        step_name = steps[request.step_number - 1].get("step_name", f"Step {request.step_number}")
    entry = append_session_entry(
        session_state,
        step_number=request.step_number,
        step_name=step_name,
        result=None,
        observation=request.spoken_text,
        flagged=contains_negative(request.spoken_text),
        user_name=request.user_name,
    )
    save_session_state(session_state)

    return {"status": "success", "entry": entry}


@app.post("/flag-issue")
async def flag_issue(request: FlagIssueRequest | None = None):
    try:
        session_state = load_session_state()
        entries = session_state["entries"]
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in session_log.json")

    if not entries:
        raise HTTPException(status_code=404, detail="No session log entries found")

    target_index = None
    target_step = request.step_number if request else None

    if target_step is not None:
        for index in range(len(entries) - 1, -1, -1):
            if entries[index].get("step_number") == target_step:
                target_index = index
                break
        if target_index is None:
            raise HTTPException(status_code=404, detail=f"No log entry found for step {target_step}")
    else:
        target_index = len(entries) - 1

    entries[target_index]["flagged"] = True
    save_session_state(session_state)

    return {"status": "success", "entry": entries[target_index]}


@app.post("/generate-report")
async def generate_report(request: GenerateReportRequest | None = None):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state()
        session_entries = session_state["entries"]
    except FileNotFoundError:
        raise HTTPException(status_code=500, detail="Required report source file not found")
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in report source file")

    request_data = request or GenerateReportRequest()
    report_format = request_data.format.lower()
    if report_format not in {"pdf", "txt"}:
        raise HTTPException(status_code=400, detail="format must be 'pdf' or 'txt'")

    effective_user_name = (
        normalize_user_name(request_data.user_name)
        or normalize_user_name(session_state.get("user_name"))
        or "UNKNOWN"
    )
    step_results = compile_step_results(sop_data, session_entries)
    report_sections = compile_report_sections(
        specimen_id=request_data.specimen_id,
        technician_name=request_data.technician_name,
        user_name=effective_user_name,
        step_results=step_results,
    )
    update_session_user_name(session_state, request_data.user_name)
    save_session_state(session_state)

    if report_format == "txt":
        generate_txt_report(
            specimen_id=request_data.specimen_id,
            technician_name=request_data.technician_name,
            user_name=effective_user_name,
            step_results=step_results,
            report_sections=report_sections,
        )
        report_path = REPORT_TXT_PATH
        download_url = "/reports/pathguard_report.txt"
    else:
        generate_pdf_report(
            specimen_id=request_data.specimen_id,
            technician_name=request_data.technician_name,
            user_name=effective_user_name,
            step_results=step_results,
            report_sections=report_sections,
        )
        report_path = REPORT_PDF_PATH
        download_url = "/reports/pathguard_report.pdf"

    return {
        "status": "success",
        "report_path": report_path,
        "format": report_format,
        "user_name": effective_user_name,
        "download_url": download_url,
        "test_name": TEST_NAME,
        "flagged_count": sum(1 for step in step_results if step["flagged"]),
    }


@app.get("/reports/{filename}")
async def download_report(filename: str):
    allowed_reports = {
        "pathguard_report.pdf": ("application/pdf", REPORT_PDF_PATH),
        "pathguard_report.txt": ("text/plain; charset=utf-8", REPORT_TXT_PATH),
    }
    if filename not in allowed_reports:
        raise HTTPException(status_code=404, detail="Report not found")

    media_type, report_path = allowed_reports[filename]
    if not os.path.exists(report_path):
        raise HTTPException(status_code=404, detail="Report file has not been generated yet")

    return FileResponse(report_path, media_type=media_type, filename=filename)


@app.get("/")
async def root():
    return {"message": "COVID PCR Voice Verification API with TTS"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
