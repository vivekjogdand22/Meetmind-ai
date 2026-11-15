# Run MeetMind, step by step

How to use this page:

- Do the steps **in the order shown**. Do not skip ahead.
- Every grey box is **one command**. Copy it, paste it into the Terminal, press Enter. Nothing else goes on that line.
- "Terminal 1", "Terminal 2" and so on are separate **tabs** in the Terminal app. Press `Command + T` for a new tab. Three of the programs keep running in their tab, so each one needs its own tab.
- After each step there is a "You should see" line. If you do not see it, stop and read "If something goes wrong" at the bottom.

---

## Part A: one-time setup

Skip whatever you have already done. The doctor tells you what is missing.

### A1. Ask the doctor what is missing

**Terminal 1.**

```bash
cd ~/Desktop/Meetmind-ai
```

```bash
./scripts/doctor.sh
```

You should see a list with `✓` (fine) and `✗` (a problem). Every `✗` has a `fix:` line under it. Do those fixes, then run the doctor again.

It is normal at this point to see `✗ PostgreSQL is not running` and "not running" for the three MeetMind programs. You start them in Part B.

### A2. Give the backend your database password (only once)

**Terminal 1.** The password file `backend/.env` stays on your computer and is never uploaded to git.

```bash
cd ~/Desktop/Meetmind-ai/backend
```

```bash
cp .env.example .env
```

```bash
open -e .env
```

A text editor opens. Change the line `DB_PASSWORD=choose-a-password` so that it contains the real password of the PostgreSQL user `meetmind_user`. Press `Command + S` to save, then close the editor.

**If you do not remember that password**, set a new one. This needs PostgreSQL to be running, so do Part B step 1 first, then:

```bash
psql postgres
```

You should see a prompt that ends with `postgres=#`. Type this line (put your own password between the quotes) and press Enter:

```sql
ALTER USER meetmind_user WITH PASSWORD 'choose-a-password';
```

You should see `ALTER ROLE`. Leave with:

```
\q
```

Then put the same password into `backend/.env`.

### A3. Download the AI model (only if the doctor said it is missing)

```bash
ollama pull qwen3:8b
```

You should see a download bar. It is a few gigabytes and takes a while.

Then download the small model that "Ask Your Meeting" uses to find the right part of a transcript:

```bash
ollama pull nomic-embed-text
```

This one is about 270 MB.

---

## Part B: start MeetMind (every time)

Open **three** Terminal tabs. The backend, the AI service and the website each keep running in their own tab.

### Step 1. Start the database

**Terminal 1.**

```bash
brew services start postgresql@17
```

You should see `Successfully started` (or `already started`, which is fine).

### Step 2. Check that the AI model runner is up

**Terminal 1.**

```bash
ollama list
```

You should see a table that contains `qwen3:8b` and `nomic-embed-text`.

If you see an error such as `could not connect to ollama app`, run this and then run `ollama list` again:

```bash
brew services start ollama
```

### Step 3. Start the backend

**Terminal 1.**

```bash
cd ~/Desktop/Meetmind-ai/backend
```

```bash
./mvnw spring-boot:run
```

Wait 10 to 20 seconds (the first time takes longer). You should see a line that ends with `Started MeetMindBackendApplication in ... seconds`.

**Leave this tab open.** The backend only works while this command is running.

### Step 4. Start the AI service

**Terminal 2** (new tab, `Command + T`).

```bash
cd ~/Desktop/Meetmind-ai/ai-service
```

```bash
source venv/bin/activate
```

Your prompt now starts with `(venv)`.

```bash
uvicorn main:app --host 127.0.0.1 --port 8000
```

You should see `Application startup complete.` and `Uvicorn running on http://127.0.0.1:8000`. The very first start also downloads the speech model, which takes a minute.

**Leave this tab open.**

### Step 5. Start the website

**Terminal 3** (new tab).

```bash
cd ~/Desktop/Meetmind-ai/frontend
```

```bash
npm run dev
```

You should see `Local:   http://localhost:5173/`.

**Leave this tab open.**

### Step 6. Open MeetMind in your browser

**Terminal 4** (another new tab).

```bash
open http://localhost:5173
```

The first time, you see a login screen. Click **Create account**, type your name, email and a password of at least 8 characters, and click **Create account**. If you uploaded meetings before this version existed, this first account receives them. Next time, use **Log in**.

Your browser opens MeetMind. Always use `localhost`, not `127.0.0.1`.

### Step 7. Check that everything is running

**Terminal 4.**

```bash
cd ~/Desktop/Meetmind-ai
```

```bash
./scripts/doctor.sh
```

You should see `All checks passed.`

### Using MeetMind

1. In the page, type a title, choose an audio or video file, and click **Generate AI insights**.
2. Wait for the green message. A 4-minute recording takes about 80 seconds.
3. Read the summary, decisions and action items. Scroll down to **Project Plan** to turn the action items into a schedule.

---

## Part C: stop everything

1. Click Terminal 1, press `Control + C`. The prompt comes back. Do the same in Terminal 2 and Terminal 3.
2. Stop the database (optional):

```bash
brew services stop postgresql@17
```

Ollama can keep running.

---

## Part D: run the automatic tests (optional, any time)

### D1. Backend tests

These need no database, no password and no AI.

```bash
cd ~/Desktop/Meetmind-ai/backend
```

```bash
./mvnw test
```

You should see `Tests run: 73, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

### D2. AI service tests

```bash
cd ~/Desktop/Meetmind-ai/ai-service
```

```bash
source venv/bin/activate
```

```bash
python -m unittest discover -s tests
```

You should see `Ran 19 tests` and `OK`.

### D3. Website code check

```bash
cd ~/Desktop/Meetmind-ai/frontend
```

```bash
npm run lint
```

You should see no problems listed.

---

## If something goes wrong

| What you see | What to do |
|---|---|
| `Connection to localhost:5432 refused` in the backend tab | The database is not running. Do Step 1, then start the backend again (Step 3) |
| `password authentication failed` in the backend tab | The password in `backend/.env` is wrong. Do A2 |
| `Port 8080 was already in use` | An old backend is still running. Press `Control + C` in the tab that runs it. To find it: `lsof -i :8080` |
| The page says "Unable to reach the MeetMind backend" | The backend (Step 3) or the database (Step 1) is not running |
| A meeting shows a yellow notice about the AI model | Ollama was not running (Step 2). Fix it and upload the recording again |
| Anything else | Run `./scripts/doctor.sh` (Step 7). It names the problem and the fix. More answers are in the Troubleshooting section of the main [README](../README.md) |
