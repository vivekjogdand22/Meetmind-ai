import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from faster_whisper import WhisperModel
from meeting_analysis import OLLAMA_MODEL, analyze_meeting_with_llm
from pydantic import BaseModel
from question_answering import (
    MAX_QUESTION_CHARS,
    AnswerError,
    answer_question,
)
from retrieval import RetrievalError


app = FastAPI(
    title="MeetMind AI Service",
    description=(
        "Local AI service for meeting transcription "
        "and action-oriented meeting analysis"
    ),
    version="2.0.0",
)


# ============================================================
# WHISPER TRANSCRIPTION MODEL
# ============================================================

whisper_model = WhisperModel(
    "base",
    device="cpu",
    compute_type="int8",
)


# ============================================================
# REQUEST MODELS
# ============================================================

class MeetingAnalysisRequest(BaseModel):
    transcript: str


class AskRequest(BaseModel):
    transcript: str
    question: str


# ============================================================
# HEALTH ENDPOINT
# ============================================================

@app.get("/health")
def health():

    return {
        "status": "UP",
        "service": "MeetMind AI Service",
        "version": "2.0.0",
        "transcriptionModel": "faster-whisper-base",
        "analysisModel": OLLAMA_MODEL,
    }


# ============================================================
# TRANSCRIPTION ENDPOINT
# ============================================================

@app.post("/transcribe")
def transcribe(
    file: UploadFile = File(...),
):

    temp_path: str | None = None

    try:

        suffix = Path(
            file.filename or "meeting-audio"
        ).suffix


        with tempfile.NamedTemporaryFile(
            delete=False,
            suffix=suffix,
        ) as temp_file:

            shutil.copyfileobj(
                file.file,
                temp_file,
            )

            temp_path = temp_file.name


        segments, info = whisper_model.transcribe(
            temp_path,
            beam_size=5,
            vad_filter=True,
        )


        transcript_parts: list[str] = []


        for segment in segments:

            text = segment.text.strip()

            if text:

                transcript_parts.append(text)


        transcript = " ".join(
            transcript_parts
        ).strip()


        if not transcript:

            raise HTTPException(
                status_code=422,
                detail=(
                    "No speech could be detected "
                    "in the uploaded recording"
                ),
            )


        return {
            "transcript": transcript,
            "language": info.language,
            "languageProbability":
                info.language_probability,
            "duration": info.duration,
        }


    except HTTPException:

        raise


    except Exception as exception:

        raise HTTPException(
            status_code=500,
            detail=(
                "Transcription failed: "
                f"{str(exception)}"
            ),
        ) from exception


    finally:

        file.file.close()

        if temp_path:

            Path(temp_path).unlink(
                missing_ok=True
            )


# ============================================================
# MEETING ANALYSIS ENDPOINT
# ============================================================

@app.post("/summarize")
def analyze_meeting(
    request: MeetingAnalysisRequest,
):

    try:

        transcript = request.transcript.strip()


        if not transcript:

            raise HTTPException(
                status_code=400,
                detail="Transcript cannot be empty",
            )


        analysis = analyze_meeting_with_llm(
            transcript
        )


        return analysis


    except HTTPException:

        raise


    except Exception as exception:

        raise HTTPException(
            status_code=500,
            detail=(
                "Meeting analysis failed: "
                f"{str(exception)}"
            ),
        ) from exception

# ============================================================
# ASK YOUR MEETING ENDPOINT
# ============================================================

@app.post("/ask")
def ask_meeting(
    request: AskRequest,
):

    transcript = request.transcript.strip()

    question = request.question.strip()


    if not transcript:

        raise HTTPException(
            status_code=400,
            detail="Transcript cannot be empty",
        )


    if not question:

        raise HTTPException(
            status_code=400,
            detail="Question cannot be empty",
        )


    if len(question) > MAX_QUESTION_CHARS:

        raise HTTPException(
            status_code=400,
            detail=(
                "The question is too long. Please keep it under "
                f"{MAX_QUESTION_CHARS} characters."
            ),
        )


    try:

        return answer_question(
            transcript,
            question,
        )


    except (RetrievalError, AnswerError) as exception:

        raise HTTPException(
            status_code=503,
            detail=str(exception),
        ) from exception


    except Exception as exception:

        raise HTTPException(
            status_code=500,
            detail=(
                "Answering the question failed: "
                f"{str(exception)}"
            ),
        ) from exception
