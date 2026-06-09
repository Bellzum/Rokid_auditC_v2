from contextlib import asynccontextmanager
from datetime import datetime, timezone
import json
import os
import re
import subprocess
import tempfile
import traceback

import requests
from fastapi import BackgroundTasks, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import Preformatted, SimpleDocTemplate, Spacer

BASE_DIR = os.path.dirname(__file__)
REPORTS_DIR = os.path.join(BASE_DIR, "reports")
os.makedirs(REPORTS_DIR, exist_ok=True)

MINIMAX_API_KEY = os.getenv("MINIMAX_API_KEY")
MINIMAX_TTS_URL = "https://api.minimax.io/v1/t2a_v2"
WHISPER_MODEL_NAME = os.getenv("WHISPER_MODEL_NAME", "tiny")
SESSION_LOG_PATH = os.path.join(BASE_DIR, "session_log.json")
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
    session_id: str | None = None
    session: dict | None = None


def load_sop_data() -> dict:
    sop_path = os.path.join(BASE_DIR, "sop.json")
    with open(sop_path, "r", encoding="utf-8") as file_obj:
        return json.load(file_obj)


def print_sop_data_on_startup() -> None:
    sop_data = load_sop_data()
    print("Loaded sop.json:")
    print(json.dumps(sop_data, indent=2))


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


@app.get("/health")
def health():
    return {"status": "ok"}


def utc_now_display() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def utc_now_file_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")


def normalize_user_name(user_name: str | None) -> str:
    if user_name is None:
        return ""
    return user_name.strip()


def normalize_timestamp(timestamp: str | None) -> str:
    if not timestamp:
        return ""
    cleaned = timestamp.strip()
    if not cleaned:
        return ""
    if cleaned.endswith(" UTC"):
        return cleaned
    try:
        parsed = datetime.fromisoformat(cleaned.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    except ValueError:
        return cleaned


def sanitize_filename_component(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9]+", "_", value.strip().lower())
    return safe.strip("_") or "unknown"


def create_default_session(sop_data: dict, user_name: str | None = None) -> dict:
    steps = []
    for index, step in enumerate(sop_data.get("steps", []), start=1):
        steps.append(
            {
                "step_number": index,
                "step_name": step.get("step_name", f"Step {index}"),
                "voice_input": "",
                "status": "pending",
                "timestamp": "",
            }
        )
    return {
        "user_name": normalize_user_name(user_name),
        "user_name_timestamp": "",
        "steps": steps,
    }


def update_session_user_name(session_state: dict, user_name: str | None, *, timestamp: str | None = None) -> None:
    normalized = normalize_user_name(user_name)
    if not normalized:
        return
    session_state["user_name"] = normalized
    if not session_state.get("user_name_timestamp"):
        session_state["user_name_timestamp"] = normalize_timestamp(timestamp) or utc_now_display()


def get_step_record(session_state: dict, step_number: int | None, step_name: str | None = None) -> dict | None:
    if step_number is None:
        return None
    steps = session_state.get("steps", [])
    if not (1 <= step_number <= len(steps)):
        return None
    step_record = steps[step_number - 1]
    if step_name:
        step_record["step_name"] = step_name
    return step_record


def update_step_state(
    session_state: dict,
    *,
    step_number: int | None,
    step_name: str | None,
    voice_input: str | None,
    status: str | None,
    timestamp: str | None = None,
) -> dict | None:
    step_record = get_step_record(session_state, step_number, step_name)
    if step_record is None:
        return None
    if voice_input is not None:
        step_record["voice_input"] = voice_input.strip()
    if status is not None:
        step_record["status"] = status
    normalized_timestamp = normalize_timestamp(timestamp)
    if normalized_timestamp:
        step_record["timestamp"] = normalized_timestamp
    elif step_record.get("status") != "pending" and not step_record.get("timestamp"):
        step_record["timestamp"] = utc_now_display()
    return step_record


def migrate_legacy_entries(entries: list[dict], session_state: dict) -> dict:
    for entry in entries:
        timestamp = normalize_timestamp(entry.get("timestamp"))
        update_session_user_name(session_state, entry.get("user_name"), timestamp=timestamp)
        step_number = entry.get("step_number")
        step_name = entry.get("step_name")
        status = "pending"
        if entry.get("flagged") or entry.get("result") == "fail":
            status = "fail"
        elif entry.get("result") == "pass":
            status = "pass"
        voice_input = entry.get("voice_input") or entry.get("observation") or ""
        if step_number is not None:
            update_step_state(
                session_state,
                step_number=step_number,
                step_name=step_name,
                voice_input=voice_input,
                status=status,
                timestamp=timestamp,
            )
    return session_state


def migrate_session_data(data: object, sop_data: dict) -> dict:
    session_state = create_default_session(sop_data)

    if isinstance(data, list):
        return migrate_legacy_entries(data, session_state)

    if not isinstance(data, dict):
        return session_state

    if isinstance(data.get("steps"), list):
        session_state["user_name"] = normalize_user_name(data.get("user_name"))
        session_state["user_name_timestamp"] = normalize_timestamp(data.get("user_name_timestamp"))
        for index, default_step in enumerate(session_state["steps"]):
            incoming = data["steps"][index] if index < len(data["steps"]) and isinstance(data["steps"][index], dict) else {}
            merged_step = {
                "step_number": incoming.get("step_number", default_step["step_number"]),
                "step_name": incoming.get("step_name", default_step["step_name"]),
                "voice_input": incoming.get("voice_input", "") or "",
                "status": incoming.get("status", "pending") or "pending",
                "timestamp": normalize_timestamp(incoming.get("timestamp")),
            }
            if merged_step["status"] != "pending" and not merged_step["timestamp"]:
                merged_step["timestamp"] = utc_now_display()
            session_state["steps"][index] = merged_step
        return session_state

    if isinstance(data.get("entries"), list):
        session_state["user_name"] = normalize_user_name(data.get("user_name"))
        session_state["user_name_timestamp"] = normalize_timestamp(data.get("user_name_timestamp"))
        return migrate_legacy_entries(data["entries"], session_state)

    return session_state


def load_session_state(sop_data: dict | None = None) -> dict:
    effective_sop = sop_data or load_sop_data()
    if not os.path.exists(SESSION_LOG_PATH):
        return create_default_session(effective_sop)
    with open(SESSION_LOG_PATH, "r", encoding="utf-8") as file_obj:
        data = json.load(file_obj)
    return migrate_session_data(data, effective_sop)


def save_session_state(session_state: dict) -> None:
    with open(SESSION_LOG_PATH, "w", encoding="utf-8") as file_obj:
        json.dump(session_state, file_obj, indent=2)


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


def resolve_step_context(spoken_text: str, requested_step_number: int | None, sop_steps: list[dict]) -> tuple[int | None, str | None]:
    if requested_step_number and 1 <= requested_step_number <= len(sop_steps):
        return requested_step_number, sop_steps[requested_step_number - 1].get("step_name", f"Step {requested_step_number}")
    return match_step_by_text(spoken_text, sop_steps)


def contains_negative(text: str) -> bool:
    return find_negative_keyword(text) is not None


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
            "pitch": 0,
        },
        "audio_setting": {
            "sample_rate": 32000,
            "bitrate": 128000,
            "format": "mp3",
            "channel": 1,
        },
    }
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    response = requests.post(MINIMAX_TTS_URL, json=payload, headers=headers, timeout=60)
    if response.status_code != 200:
        raise HTTPException(status_code=response.status_code, detail=f"MiniMax API error: {response.text}")
    result = response.json()
    if result.get("base_resp", {}).get("status_code") != 0:
        raise HTTPException(status_code=500, detail=result.get("base_resp", {}).get("status_msg", "MiniMax API error"))
    audio_hex = result.get("data", {}).get("audio")
    if not audio_hex:
        raise HTTPException(status_code=500, detail="MiniMax API returned no audio")
    return bytes.fromhex(audio_hex), result


def play_audio(audio_bytes: bytes) -> None:
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
    audio_bytes, _ = text_to_speech(text=text, voice_id="female-shaonv", model="speech-2.6-turbo")
    play_audio(audio_bytes)


def session_step_snapshot(step_record: dict) -> dict:
    return {
        "step_number": step_record.get("step_number"),
        "step_name": step_record.get("step_name"),
        "voice_input": step_record.get("voice_input", ""),
        "status": step_record.get("status", "pending"),
        "timestamp": normalize_timestamp(step_record.get("timestamp")),
    }


def build_report_lines(session_state: dict, generated_timestamp: str) -> list[str]:
    separator = "================================"
    lines = [
        separator,
        "AUDIT C - LAB QUALITY CONTROL REPORT",
        separator,
        f"Technician : {session_state.get('user_name') or 'UNKNOWN'}",
        f"Login time  : {session_state.get('user_name_timestamp') or 'Not recorded'}",
        f"Generated   : {generated_timestamp}",
        separator,
        "",
        "STEP RESULTS",
        "--------------------------------",
        "Step 0 - Technician name",
        f"  Input     : {session_state.get('user_name') or 'UNKNOWN'}",
        f"  Timestamp : {session_state.get('user_name_timestamp') or 'Not recorded'}",
        f"  Status    : {'PASS' if session_state.get('user_name') else 'PENDING'}",
        "",
    ]
    for step in session_state.get("steps", []):
        status = (step.get("status") or "pending").upper()
        lines.extend(
            [
                f"Step {step.get('step_number')} - {step.get('step_name')}",
                f"  Input     : {step.get('voice_input') or '(not recorded)'}",
                f"  Timestamp : {step.get('timestamp') or 'Not recorded'}",
                f"  Status    : {status}",
                "",
            ]
        )
    lines.extend([separator, "END OF REPORT", separator])
    return lines


def create_report_file_paths(user_name: str, report_format: str) -> tuple[str, str]:
    timestamp = utc_now_file_stamp()
    safe_user_name = sanitize_filename_component(user_name)
    file_name = f"auditc_{safe_user_name}_{timestamp}.{report_format}"
    return file_name, os.path.join(REPORTS_DIR, file_name)


def generate_txt_report(report_lines: list[str], report_path: str) -> None:
    with open(report_path, "w", encoding="utf-8") as file_obj:
        file_obj.write("\n".join(report_lines))


def generate_pdf_report(report_lines: list[str], report_path: str) -> None:
    doc = SimpleDocTemplate(
        report_path,
        pagesize=letter,
        leftMargin=0.6 * inch,
        rightMargin=0.6 * inch,
        topMargin=0.6 * inch,
        bottomMargin=0.6 * inch,
    )
    styles = getSampleStyleSheet()
    mono_style = ParagraphStyle(
        "AuditCMono",
        parent=styles["Code"],
        fontName="Courier",
        fontSize=10,
        leading=14,
    )
    story = [
        Preformatted("\n".join(report_lines), mono_style),
        Spacer(1, 0.1 * inch),
    ]
    doc.build(story)


@app.post("/verify-step", response_model=VerifyResponse)
async def verify_step(request: VerifyRequest, background_tasks: BackgroundTasks):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state(sop_data)
    except FileNotFoundError:
        raise HTTPException(status_code=500, detail="Required verification source file not found")
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in verification source file")

    update_session_user_name(session_state, request.user_name)
    step_number, step_name = resolve_step_context(request.spoken_text, request.step_number, sop_data.get("steps", []))
    matched_keyword = find_negative_keyword(request.spoken_text)
    timestamp = utc_now_display()
    status = "fail" if matched_keyword else "pass"
    update_step_state(
        session_state,
        step_number=step_number,
        step_name=step_name,
        voice_input=request.spoken_text,
        status=status,
        timestamp=timestamp,
    )
    save_session_state(session_state)

    if matched_keyword and MINIMAX_API_KEY:
        background_tasks.add_task(speak_alert, "Warning. Issue detected.")
    elif MINIMAX_API_KEY:
        background_tasks.add_task(speak_alert, "Verified.")

    return VerifyResponse(
        result=status,
        step_name=step_name,
        timestamp=timestamp,
        matched_keyword=matched_keyword or "positive",
    )


@app.post("/transcribe")
async def transcribe_audio(request: Request, audio: UploadFile | None = File(default=None)):
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
            model=request.model,
        )
        return {
            "status": "success",
            "audio_hex": audio_bytes.hex(),
            "voice_id": request.voice_id,
            "model": request.model,
            "trace_id": result.get("trace_id"),
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/tts/speak")
async def tts_speak_endpoint(request: TTSRequest):
    try:
        audio_bytes, result = text_to_speech(
            text=request.text,
            voice_id=request.voice_id,
            speed=request.speed,
            model=request.model,
        )
        play_audio(audio_bytes)
        return {
            "status": "success",
            "text": request.text,
            "voice_id": request.voice_id,
            "model": request.model,
            "trace_id": result.get("trace_id"),
        }
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/log-observation")
async def log_observation(request: LogObservationRequest):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state(sop_data)
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in session_log.json")

    update_session_user_name(session_state, request.user_name)
    step_name = None
    steps = sop_data.get("steps", [])
    if 1 <= request.step_number <= len(steps):
        step_name = steps[request.step_number - 1].get("step_name", f"Step {request.step_number}")
    status = "fail" if contains_negative(request.spoken_text) else None
    timestamp = utc_now_display()
    step_record = update_step_state(
        session_state,
        step_number=request.step_number,
        step_name=step_name,
        voice_input=request.spoken_text,
        status=status,
        timestamp=timestamp if status else None,
    )
    save_session_state(session_state)
    return {
        "status": "success",
        "entry": session_step_snapshot(step_record) if step_record else None,
        "flagged": contains_negative(request.spoken_text),
    }


@app.post("/flag-issue")
async def flag_issue(request: FlagIssueRequest | None = None):
    try:
        sop_data = load_sop_data()
        session_state = load_session_state(sop_data)
    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="Invalid JSON in session_log.json")

    target_step = request.step_number if request else None
    if target_step is None:
        completed_steps = [step for step in session_state.get("steps", []) if step.get("timestamp")]
        if completed_steps:
            target_step = completed_steps[-1].get("step_number")
    if target_step is None:
        raise HTTPException(status_code=404, detail="No session step found to flag")

    step_record = update_step_state(
        session_state,
        step_number=target_step,
        step_name=None,
        voice_input=None,
        status="fail",
        timestamp=utc_now_display(),
    )
    save_session_state(session_state)
    return {"status": "success", "entry": session_step_snapshot(step_record) if step_record else None}


@app.post("/generate-report")
async def generate_report(request: GenerateReportRequest | None = None):
    try:
        sop_data = load_sop_data()
        request_data = request or GenerateReportRequest()
        session_state = migrate_session_data(request_data.session, sop_data) if request_data.session else load_session_state(sop_data)
        update_session_user_name(session_state, request_data.user_name)
        if not session_state.get("user_name_timestamp") and session_state.get("user_name"):
            session_state["user_name_timestamp"] = utc_now_display()
        for step in session_state.get("steps", []):
            if step.get("status") != "pending" and not step.get("timestamp"):
                step["timestamp"] = utc_now_display()

        report_format = request_data.format.lower()
        if report_format not in {"pdf", "txt"}:
            return {"error": "format must be 'pdf' or 'txt'"}

        generated_timestamp = utc_now_display()
        report_lines = build_report_lines(session_state, generated_timestamp)
        file_name, report_path = create_report_file_paths(session_state.get("user_name") or request_data.user_name or "unknown", report_format)

        if report_format == "pdf":
            generate_pdf_report(report_lines, report_path)
        else:
            generate_txt_report(report_lines, report_path)

        save_session_state(session_state)
        return {
            "status": "success",
            "format": report_format,
            "file_name": file_name,
            "report_path": report_path,
            "download_url": f"/reports/{file_name}",
            "session_id": request_data.session_id,
            "user_name": session_state.get("user_name") or "UNKNOWN",
        }
    except Exception as exc:
        traceback.print_exc()
        return {"error": str(exc)}


@app.get("/reports/{filename}")
async def download_report(filename: str):
    safe_filename = os.path.basename(filename)
    report_path = os.path.join(REPORTS_DIR, safe_filename)
    if not os.path.exists(report_path):
        raise HTTPException(status_code=404, detail="Report file has not been generated yet")
    if safe_filename.endswith(".pdf"):
        media_type = "application/pdf"
    elif safe_filename.endswith(".txt"):
        media_type = "text/plain; charset=utf-8"
    else:
        raise HTTPException(status_code=404, detail="Report not found")
    return FileResponse(report_path, media_type=media_type, filename=safe_filename)


@app.get("/")
async def root():
    return {"message": "COVID PCR Voice Verification API with TTS"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
