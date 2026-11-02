# Evaluating the AI extraction

How good is the AI at finding tasks, owners, due dates, priorities and decisions?
This folder answers that with numbers, so a change to the prompt or the settings
can be judged by results instead of by feeling.

It holds 7 test meetings, each with an answer key. `run_eval.py` sends every
meeting to the running AI service and compares what comes back with the key.

## Run it

Start the AI service in one terminal (and keep Ollama running):

```bash
cd ai-service
source venv/bin/activate
uvicorn main:app --host 127.0.0.1 --port 8000
```

In another terminal:

```bash
cd ai-service
source venv/bin/activate
python eval/run_eval.py
```

That runs every meeting and takes several minutes. Two variations:

```bash
python eval/run_eval.py sample standup
```

runs only the meetings you name, and

```bash
python eval/run_eval.py --show
```

also prints what the model extracted.

While it runs, watch the AI service terminal. A line saying
`Using rule-based fallback` means the language model failed for that meeting
and the simple fallback answered instead.

## The test meetings (`meetings/`)

| Meeting | Words | What it tests |
|---|---|---|
| `sample` | 134 | A chain of four dependent steps with owners and dates, one HIGH and one LOW priority task, "next Monday" style dates |
| `standup` | 159 | Status updates ("I finished the login page") that must **not** become tasks, "today" and "tomorrow", a postponed feature |
| `planning` | 227 | Seven tasks, urgency words, "no rush", dates like "the twelfth", a side task for Legal, three decisions |
| `no_actions` | 102 | Pure discussion. The right answer is *nothing*, so it catches invented work |
| `kickoff` | 143 | Dependencies ("only after", "once both are finished") and durations, the kind of meeting the project planner is built for |
| `messy` | 119 | Filler words and false starts like raw speech-to-text, and a parked topic that must not become a task |
| `long` | 4,483 | About 30 minutes of neutral talk with four tasks and a decision planted at the start and at the quarter points. Tests the context window and recall. Built by `make_long_meeting.py` |

The meetings are written text, not recordings, so they test the analysis step only (not Whisper).

## How the scoring works

For each meeting the table shows how many of the key's items were found:

| Column | Meaning |
|---|---|
| `tasks` | Key tasks found. A task counts as found when an extracted task contains all of its keywords |
| `owner`, `due`, `prio` | Out of the tasks that were found: owner matches ("Team", "the team" and "we" count as the same), due date contains an accepted text (or is empty when `null` is accepted), priority is exactly right |
| `extra` | Extracted tasks that match nothing in the key. Tasks listed under `optional` are not counted against the model |
| `decisions` | Key decisions found |

Below the table, **Details** lists every miss. It also flags the text `null` (or `none`, `n/a`)
written where a real empty value belongs. The service cleans these up, but it is worth knowing
whether the model produced them.

## The answer key

Each meeting is a JSON file:

```json
{
  "name": "standup",
  "description": "What this meeting tests",
  "transcript": "The text that is sent to the AI ...",
  "gold": {
    "tasks": [
      {"kw": ["upload", "bug"], "owner": "Rahul", "due": ["today"], "priority": "MEDIUM"},
      {"kw": ["release notes"], "owner": "Unassigned", "due": ["next week", null], "priority": "LOW"}
    ],
    "optional": [{"kw": ["demo"]}],
    "decisions": [{"kw": ["dark mode"]}]
  }
}
```

- `kw` are lowercase words that must all appear in the extracted task. `"a|b"` means either one.
- `due` lists every acceptable text. `null` means "no date" is acceptable.
- To add a meeting, copy a file in `meetings/`, change it, and run `python eval/run_eval.py yourname`.
  Read the **Details** once to check that your keywords are not too strict or too loose.

## Reference results

Qwen3 8B through Ollama, thinking off, on an Apple M2 with 16 GB (2 November 2026):

| Meeting | tasks | owner | due | prio | extra | decisions |
|---|---|---|---|---|---|---|
| kickoff | 6/6 | 6/6 | 6/6 | 6/6 | 0 | 1/1 |
| long | 3/4 | 3/3 | 3/3 | 3/3 | 0 | 1/1 |
| messy | 2/2 | 2/2 | 2/2 | 2/2 | 0 | 1/2 |
| no_actions | 0/0 | - | - | - | 0 | 0/0 |
| planning | 6/7 | 6/6 | 6/6 | 5/6 | 0 | 3/3 |
| sample | 6/6 | 6/6 | 6/6 | 6/6 | 0 | 2/2 |
| standup | 4/4 | 4/4 | 4/4 | 4/4 | 0 | 1/1 |
| **Total** | **27/29** | **27/27** | **27/27** | **26/27** | **0** | **9/10** |

(In `messy`, the invoice fix has two dates in the transcript, "tomorrow morning" and "before Friday". The key accepts both; the first run used a stricter key and counted "before Friday" as wrong.)

What was missed: the task said at the very start of the 30-minute `long` meeting
(the budget proposal), "Priya will email Legal" in `planning`, the parked dashboard in
`messy` (a soft decision), and "no rush" was rated MEDIUM instead of LOW in `planning`.

## Reading the numbers honestly

- Seven short, synthetic meetings are a small sample. Treat the results as a guide, not a guarantee.
- Even tiny changes can flip a borderline item. Asking Ollama for the same context size explicitly once changed one stand-up task from found to missed. Run the suite more than once, and prefer changes that help several meetings.
- A change is worth keeping when the totals improve and nothing that used to work breaks.
- The seconds column depends on what else the computer is doing. Compare timings only between back-to-back runs.
