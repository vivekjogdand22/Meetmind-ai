# The Python AI service: Faster-Whisper for speech, Ollama (on your computer)
# for the language model.

FROM python:3.12-slim
WORKDIR /app
COPY requirements-docker.txt ./
RUN pip install --no-cache-dir -r requirements-docker.txt
COPY main.py meeting_analysis.py question_answering.py retrieval.py ./

# the speech model is downloaded on first use and kept in this folder
ENV HF_HOME=/models
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
