# 🧠 MeetMind AI

AI-powered meeting intelligence platform that converts meeting recordings into structured insights using speech-to-text and Large Language Models.

---
## 🚀 Features

- 🎙 Upload audio/video meetings
- 📝 Automatic speech-to-text transcription
- 📄 AI-generated meeting summary
- ✅ Key decision extraction
- 📌 Action item generation
- 👥 Owner assignment
- ⏰ Due date extraction
- 🔥 Priority detection
- 📚 Meeting history
- 🗑 Delete a meeting (removes its recording, results and plan)
- 💬 **Ask Your Meeting**: ask a question about one meeting and get an answer taken only from its transcript, with the exact quoted evidence, or "Not discussed"
- 🗓 PERT/CPM project planning: turn action items into a schedule with expected times, slack, the critical path and a timeline, plus a deadline check and a "what if a task is late?" simulation
- 🎨 Modern React UI

---
## 🛠 Tech Stack

### Frontend
- React 19 with Vite
- CSS3
- Lucide icons
- Fetch API

### Backend
- Java 21
- Spring Boot 3 (REST, Spring Data JPA)
- Maven
- PostgreSQL

### AI Service
- Python with FastAPI
- Faster-Whisper (speech-to-text)
- Qwen3 8B, run locally through Ollama

---
## 🏗 Architecture

```
Frontend (React)
        │
        ▼
Spring Boot Backend
        │
        ▼
Python AI Service
        │
        ├── Faster Whisper
        └── Qwen3 LLM
```

---
## 📁 Project Structure

```
Meetmind-ai
│
├── frontend        React + Vite user interface
├── backend         Spring Boot REST API and PostgreSQL persistence
├── ai-service      FastAPI service: Whisper transcription and Qwen3 analysis
│   └── eval        Test meetings and scorer for the AI extraction quality
├── docs            API notes and the API smoke test
├── scripts         doctor.sh: checks what is wrong when something does not start
├── screenshots
└── README.md
```

---
## 🤖 AI Workflow

1. Upload meeting recording
2. Convert speech to text using Faster-Whisper
3. Generate AI summary using Qwen3
4. Extract key decisions
5. Extract action items
6. Identify owners
7. Detect priorities
8. Extract due dates
9. Display results in the dashboard

---
## 📸 Screenshots

### 🏠 Home Page (Top)

![Home Page](screenshots/home-1.png)

---

### 🏠 Home Page (Workspace)

![Workspace](screenshots/home-2.png)

---

### ⚙️ AI Processing

![Processing](screenshots/processing.png)

---

### 📊 AI Generated Insights

![Results](screenshots/results.png)

---

### 📜 Transcript

![Transcript](screenshots/transcript.png)

---
## ⚙️ Installation

> **Starting MeetMind every day?** Follow [docs/run-step-by-step.md](docs/run-step-by-step.md). It lists every command in order, one per box, with what you should see after each one.

### Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Java (JDK) | 21 | Spring Boot backend |
| Node.js and npm | a recent LTS release | React frontend |
| Python | 3.10 or newer | AI service |
| PostgreSQL | 17 (any recent version) | Storing meetings |
| [Ollama](https://ollama.com) | a recent release (developed with 0.31) | Runs the Qwen3 model locally |

### 1. Clone the Repository

```bash
git clone https://github.com/vivekjogdand22/Meetmind-ai.git
cd Meetmind-ai
```

### 2. Create the Database

Start PostgreSQL (on macOS with Homebrew: `brew services start postgresql@17`), then create the database and user the backend expects:

```bash
psql postgres
```

```sql
CREATE USER meetmind_user WITH PASSWORD 'choose-a-password';
CREATE DATABASE meetmind_db OWNER meetmind_user;
```

The tables are created automatically the first time the backend starts.

---

### 3. Start the Backend

The database password has no default. The easiest way is a `backend/.env` file, which git ignores, so you do not have to export the password in every terminal. First copy the example file:

```bash
cd backend
cp .env.example .env
```

Then open `backend/.env` in an editor and set `DB_PASSWORD` to your password. Now start the backend:

```bash
./mvnw spring-boot:run
```

(Instead of the file you can run `export DB_PASSWORD='choose-a-password'` before starting.) `DB_URL`, `DB_USERNAME`, `AI_SERVICE_URL` and `CORS_ALLOWED_ORIGINS` can be set the same way. Start the backend from the `backend` folder so that the `.env` file is found.

Note for the macOS terminal (zsh): do not type a `#` comment after a command. zsh does not treat it as a comment and passes it to the command as extra arguments.

The Spring Boot backend will start on:

```
http://localhost:8080
```

---

### 4. Set Up and Start the AI Service

Download the language model once and keep Ollama running:

```bash
ollama pull qwen3:8b
```

Then download the small model that finds the right part of a transcript when you ask a question (about 270 MB):

```bash
ollama pull nomic-embed-text
```

Then, in a new terminal, create the Python environment. You only do this the first time:

```bash
cd ai-service
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Now start the service:

```bash
uvicorn main:app --host 127.0.0.1 --port 8000
```

Next time, open a terminal, run `cd ai-service` and `source venv/bin/activate`, and then the `uvicorn` command above.

`main.py` has no startup code of its own, so the service has to be started with `uvicorn`; running `python main.py` does not start the server. The first start also downloads the Whisper `base` model.

The AI service will start on:

```
http://127.0.0.1:8000
```

Open `http://127.0.0.1:8000/health` to check that it is running.

---

### 5. Start the Frontend

Open another terminal.

```bash
cd frontend
npm install
npm run dev
```

The React application will start on:

```
http://localhost:5173
```

---

### 6. Open the Application

Open your browser and visit:

```
http://localhost:5173
```

Upload an audio or video meeting and MeetMind AI will automatically:

- 🎙 Transcribe the recording
- 📝 Generate an AI summary
- ✅ Extract key decisions
- 📌 Generate action items
- 👤 Assign owners
- ⏰ Detect due dates
- 🔥 Detect priorities

---
## ⏱ What to Expect

Measured on an Apple M2 laptop with 16 GB of RAM, a real 4-minute recording (620 words) went from clicking **Generate AI insights** to the finished results in about 80 seconds: roughly 30 seconds for transcription and saving, and about 50 seconds for the AI analysis. The AI analysis of a short transcript (a few hundred words) takes roughly 30 seconds to 2 minutes, and the transcript of a 30-minute meeting takes 2 to 3 minutes to analyze. The first request after Ollama has been idle is slower because the model has to be loaded again.

---
## 🗓 Using the Project Plan

Every meeting has a **Project Plan** panel under its action items. It turns the action items into a schedule with PERT and CPM.

1. Click **Create plan from N action items** (or **Add a task manually**).
2. For every task, click the pencil and enter three estimates in days: **optimistic** (best case), **most likely** and **pessimistic** (worst case). Tick the tasks that must finish **before** this one can start.
3. Once every task has its estimates, the panel shows the project duration, the critical path, a timeline, and for each task its expected time, start day, end day and slack. The expected time is `(optimistic + 4 x most likely + pessimistic) / 6`.
4. A task with 0 days of slack is **critical**: if it is late, the whole project is late. The dashed part of a bar in the timeline shows how far a task can slip without delaying anything.
5. **Deadline check:** type a number of days to see how likely the project is to finish within that time, based on the spread of your estimates.
6. **What if a task is late?** Pick a task and a delay. The panel says whether the finish date moves, which tasks finish later, and shows the timeline as it would look. This is only a simulation: nothing is saved.

The plan is a copy of the action items. Editing it does not change the list the AI extracted, and deleting a meeting deletes its plan. The endpoints are described in `docs/project-plan-api.md`.

---
## 💬 Using Ask Your Meeting

Every meeting has an **Ask Your Meeting** panel above the project plan.

1. Type a question, or click one of the example questions.
2. The answer comes **only from this meeting's transcript**. Under it you see the passage the answer rests on, with the exact words highlighted. These words are checked against the transcript by code, not just by the AI.
3. If the meeting does not talk about it, you get **Not discussed**. It never answers from general knowledge.
4. If the AI gives an answer that the transcript cannot confirm, you see **Could not confirm an answer**. Try other words.

It works best for one fact at a time ("Who owns the Stripe integration?"). A broad question such as "summarize everything" only sees the closest passages, and the panel says so. Questions are not saved. The first question about a meeting takes longer (up to about a minute) because the transcript is indexed once; later questions are faster.

Needs Ollama running with both models (`qwen3:8b` and `nomic-embed-text`). If one is missing, the panel tells you what to run.

---
## 🔧 AI Service Settings

These constants are at the top of `ai-service/meeting_analysis.py`:

| Setting | Default | Meaning |
|---|---|---|
| `OLLAMA_THINK` | `False` | Qwen3 normally reasons silently before it answers. In testing that made each analysis 2 to 3 times slower and produced invented calendar dates. Set it to `True` for a more thorough but slower analysis. |
| `CONTEXT_SIZES` | `(4096, 8192, 16384, 32768)` | The model's context window is chosen to fit the transcript. Ollama's default window of 4096 tokens silently cuts off longer recordings. |
| `OLLAMA_TIMEOUT_SECONDS` | `600` | How long the service waits for the model before it switches to the simple rule-based fallback. |

Two environment variables change where the AI service looks for Ollama: `OLLAMA_URL` (default `http://127.0.0.1:11434/api/chat`) and `OLLAMA_MODEL` (default `qwen3:8b`). Ask Your Meeting also uses `OLLAMA_EMBED_URL` (default `http://127.0.0.1:11434/api/embed`) and `OLLAMA_EMBED_MODEL` (default `nomic-embed-text`).

If the AI service terminal prints `Using rule-based fallback`, the language-model step failed and the result is of lower quality. The meeting then shows a yellow notice saying so, so you know to start Ollama and upload the recording again.

---
## 🩺 Troubleshooting

Run the doctor first. It only reads, and it tells you what is wrong and how to fix it:

```bash
./scripts/doctor.sh
```

| What you see | Why | What to do |
|---|---|---|
| `Connection to localhost:5432 refused` | PostgreSQL is not running | `brew services start postgresql@17` |
| `password authentication failed for user "meetmind_user"` | The password is wrong, or `DB_PASSWORD` is not set, so PostgreSQL did not get the right one | Create `backend/.env` from `backend/.env.example` (or `export DB_PASSWORD='...'`). To reset the user's password: `ALTER USER meetmind_user WITH PASSWORD '...'` |
| `Port 8080 was already in use` | An old backend is still running | `lsof -i :8080`, then stop that process |
| The page says "Unable to reach the MeetMind backend" | The backend or PostgreSQL is not running | Start them (see the steps above), then run the doctor |
| The browser console says a request was "blocked by CORS policy" | The page was opened from an address the backend does not allow | Open `http://localhost:5173`. To allow another address, set `CORS_ALLOWED_ORIGINS` and restart the backend |
| Ask Your Meeting says Ollama is not reachable, or that a model is not installed | Ollama is not running, or `nomic-embed-text` was not downloaded | Run `brew services start ollama`, then `ollama pull nomic-embed-text` |
| A meeting shows a yellow "The AI model was not available" notice | Ollama was not running (or the model was missing) when the meeting was analyzed | Start Ollama, run `ollama pull qwen3:8b`, then upload the recording again |
| `./mvnw: Permission denied` | The wrapper script is not executable | `chmod +x backend/mvnw` |
| A meeting has no "Create plan from ..." button | The meeting has no action items | Use **Add a task manually** |
| The page says `No speech could be detected in the uploaded recording` | The recording has no speech the AI can hear (silent video, music only, wrong file) | Check that the file plays with clear speech and upload a better one. The failed attempt stays in the list, and you can remove it with **Delete meeting** |
| The page says `The AI service is not reachable at http://127.0.0.1:8000` | The Python AI service is not running | Start it (step 4 of the installation) and upload again |

The Vite dev server listens on `localhost`, so open `http://localhost:5173` (typing `127.0.0.1` instead may not load the page).

---
## 🧪 Tests

- Backend (86 tests, no PostgreSQL, password, Whisper or Ollama needed: they use an in-memory database and a stand-in for the AI service, and cover the whole upload pipeline): `cd backend && ./mvnw test`
- AI service logic (119 tests, no Ollama needed): `cd ai-service && python -m unittest discover -s tests -v`
- Project-plan API against real PostgreSQL and a running backend: `python3 docs/plan_api_smoke_test.py`. The endpoints are described in `docs/project-plan-api.md`.
- AI extraction quality: start the AI service, then run `python eval/run_eval.py` from the `ai-service` folder. See `ai-service/eval/README.md`.
- Ask Your Meeting quality (needs Ollama with both models): `python eval/run_qa_eval.py --answers` from the `ai-service` folder grades 28 questions, including ones the meeting does not answer. The number that must stay at 0 is *invented answers*.

---
## 🚀 Future Improvements

The following enhancements are planned for future versions of MeetMind AI:

- 🔍 Search meetings by title or keyword
- 📄 Export meeting summary as PDF
- 📑 Export transcript as DOCX
- 📊 Meeting analytics dashboard
- 👥 Speaker identification
- 🔐 User authentication and authorization
- ☁ Cloud deployment (AWS/Azure/GCP)
- 📱 Responsive mobile-first interface
- 🌍 Multi-language transcription and translation
- 🔔 Email notifications for action items
- 📅 Calendar integration (Google Calendar / Outlook)
- 📈 Team productivity insights
- 🤖 Support for additional AI models
- ⚡ Real-time meeting transcription

---
## 👨‍💻 Developed By

**Vivek Jogdand**

Computer Engineering Student

### Technologies Used

- React.js
- Spring Boot
- Python
- Faster-Whisper
- Qwen3
- REST API

---
