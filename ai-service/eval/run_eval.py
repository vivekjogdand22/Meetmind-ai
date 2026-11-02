"""Scores the AI service on the hand-written test meetings in eval/meetings.

Start the AI service first:

    uvicorn main:app --host 127.0.0.1 --port 8000

Then, from the ai-service folder, run every meeting:

    python eval/run_eval.py

or only the meetings you name:

    python eval/run_eval.py sample standup

or also print what the model extracted:

    python eval/run_eval.py --show

Each meeting file holds a transcript and an answer key ("gold"). The script
sends the transcript to POST /summarize and compares the answer with the key.
See eval/README.md for how the scoring works.
"""

import argparse
import json
import sys
import time
from pathlib import Path

import requests


MEETINGS_DIR = Path(__file__).parent / "meetings"

TEAM_NAMES = {"team", "the team", "we"}

EMPTY_TEXT = {"null", "none", "n/a", "unknown"}


# ============================================================
# LOADING
# ============================================================

def load_meetings(names: list[str]) -> list[dict]:

    files = sorted(MEETINGS_DIR.glob("*.json"))

    meetings = [
        json.loads(file.read_text())
        for file in files
    ]

    if not names:
        return meetings

    known = {meeting["name"] for meeting in meetings}

    unknown = [name for name in names if name not in known]

    if unknown:
        sys.exit(
            f"Unknown meeting(s): {', '.join(unknown)}. "
            f"Available: {', '.join(sorted(known))}"
        )

    return [meeting for meeting in meetings if meeting["name"] in names]


# ============================================================
# MATCHING HELPERS
# ============================================================

def normalize(value):

    if isinstance(value, str):
        return value.strip().lower()

    return value


def has_all_keywords(text: str, keywords: list[str]) -> bool:

    # Every keyword must appear in the text. A keyword written as
    # "a|b" is satisfied by either "a" or "b".
    text = normalize(text)

    return all(
        any(option in text for option in keyword.split("|"))
        for keyword in keywords
    )


def owner_matches(actual: str, expected: str) -> bool:

    actual, expected = normalize(actual), normalize(expected)

    if expected in TEAM_NAMES:
        return actual in TEAM_NAMES

    return actual == expected


def due_matches(actual, accepted: list) -> bool:

    # accepted lists the texts that are fine; null means "no date" is fine.
    actual = normalize(actual)

    if actual is None:
        return None in accepted

    return any(
        text is not None and text in actual
        for text in accepted
    )


def find_item(items: list[dict], used: set[int], keywords: list[str]):

    for index, item in enumerate(items):

        if index not in used and has_all_keywords(item["task"], keywords):
            return index

    return None


# ============================================================
# SCORING
# ============================================================

def score_meeting(analysis: dict, gold: dict) -> dict:

    items = analysis.get("actionItems", [])

    decisions = analysis.get("keyDecisions", [])

    used: set[int] = set()

    score = {
        "tasks_found": 0,
        "tasks_total": len(gold["tasks"]),
        "owner_ok": 0,
        "due_ok": 0,
        "priority_ok": 0,
        "extra_tasks": 0,
        "decisions_found": 0,
        "decisions_total": len(gold["decisions"]),
        "empty_text": 0,
        "problems": [],
    }

    problems = score["problems"]


    for expected in gold["tasks"]:

        label = " + ".join(expected["kw"])

        index = find_item(items, used, expected["kw"])

        if index is None:
            problems.append(f"missed task: {label}")
            continue

        used.add(index)

        item = items[index]

        score["tasks_found"] += 1

        if owner_matches(item["owner"], expected["owner"]):
            score["owner_ok"] += 1
        else:
            problems.append(
                f"wrong owner for {label}: got {item['owner']!r}, "
                f"expected {expected['owner']!r}"
            )

        if due_matches(item["dueDate"], expected["due"]):
            score["due_ok"] += 1
        else:
            problems.append(
                f"wrong due date for {label}: got {item['dueDate']!r}, "
                f"expected one of {expected['due']}"
            )

        if item["priority"] == expected["priority"]:
            score["priority_ok"] += 1
        else:
            problems.append(
                f"wrong priority for {label}: got {item['priority']}, "
                f"expected {expected['priority']}"
            )


    optional = gold.get("optional", [])

    for index, item in enumerate(items):

        if index in used:
            continue

        if any(has_all_keywords(item["task"], entry["kw"]) for entry in optional):
            continue

        score["extra_tasks"] += 1

        problems.append(f"extra task: {item['task']}")


    for expected in gold["decisions"]:

        if any(has_all_keywords(text, expected["kw"]) for text in decisions):
            score["decisions_found"] += 1
        else:
            problems.append(f"missed decision: {' + '.join(expected['kw'])}")


    for item in items:

        for field in ("owner", "dueDate"):

            if normalize(item[field]) in EMPTY_TEXT:
                score["empty_text"] += 1
                problems.append(
                    f"{field} is the text {item[field]!r} instead of a real null"
                )

    return score


# ============================================================
# RUNNING
# ============================================================

def analyze(base_url: str, transcript: str):

    started = time.time()

    try:
        response = requests.post(
            f"{base_url}/summarize",
            json={"transcript": transcript},
            timeout=900,
        )

        response.raise_for_status()

    except requests.RequestException as exception:
        return None, time.time() - started, str(exception)

    return response.json(), time.time() - started, None


def fraction(found: int, total: int) -> str:

    return f"{found}/{total}"


def main() -> None:

    parser = argparse.ArgumentParser(description="Score the MeetMind AI service")

    parser.add_argument("meetings", nargs="*", help="meeting names (default: all)")

    parser.add_argument("--url", default="http://127.0.0.1:8000")

    parser.add_argument("--show", action="store_true", help="print the extracted items")

    args = parser.parse_args()


    try:
        requests.get(f"{args.url}/health", timeout=5).raise_for_status()
    except requests.RequestException:
        sys.exit(
            f"The AI service is not reachable at {args.url}. Start it with:\n"
            "    uvicorn main:app --host 127.0.0.1 --port 8000"
        )


    print(
        f"{'meeting':<12}{'words':>6}{'seconds':>9}  {'tasks':>6}{'owner':>7}"
        f"{'due':>6}{'prio':>6}{'extra':>7}  {'decisions':>9}"
    )

    totals = {
        "tasks_found": 0, "tasks_total": 0, "owner_ok": 0, "due_ok": 0,
        "priority_ok": 0, "extra_tasks": 0, "decisions_found": 0,
        "decisions_total": 0, "empty_text": 0,
    }

    problem_lines: list[str] = []


    for meeting in load_meetings(args.meetings):

        name = meeting["name"]

        words = len(meeting["transcript"].split())

        analysis, seconds, error = analyze(args.url, meeting["transcript"])

        if analysis is None:
            print(f"{name:<12}{words:>6}{seconds:>9.0f}  FAILED: {error}")
            continue

        score = score_meeting(analysis, meeting["gold"])

        found = score["tasks_found"]

        print(
            f"{name:<12}{words:>6}{seconds:>9.0f}  "
            f"{fraction(found, score['tasks_total']):>6}"
            f"{fraction(score['owner_ok'], found):>7}"
            f"{fraction(score['due_ok'], found):>6}"
            f"{fraction(score['priority_ok'], found):>6}"
            f"{score['extra_tasks']:>7}  "
            f"{fraction(score['decisions_found'], score['decisions_total']):>9}"
        )

        for key in totals:
            totals[key] += score[key]

        for problem in score["problems"]:
            problem_lines.append(f"  [{name}] {problem}")

        if args.show:
            problem_lines.append(f"  [{name}] extracted:")
            for item in analysis.get("actionItems", []):
                problem_lines.append(
                    f"      - {item['owner']} | {item['dueDate']} | "
                    f"{item['priority']} | {item['task']}"
                )
            for text in analysis.get("keyDecisions", []):
                problem_lines.append(f"      * decision: {text}")


    found = totals["tasks_found"]

    print(
        f"{'TOTAL':<12}{'':>6}{'':>9}  "
        f"{fraction(found, totals['tasks_total']):>6}"
        f"{fraction(totals['owner_ok'], found):>7}"
        f"{fraction(totals['due_ok'], found):>6}"
        f"{fraction(totals['priority_ok'], found):>6}"
        f"{totals['extra_tasks']:>7}  "
        f"{fraction(totals['decisions_found'], totals['decisions_total']):>9}"
    )

    if problem_lines:
        print("\nDetails:")
        print("\n".join(problem_lines))

    print(
        "\nThe table compares the model's answer with the hand-written key. "
        "'owner', 'due' and 'prio' count only the tasks that were found."
    )

    print(
        "Watch the AI service terminal: a line saying 'Using rule-based "
        "fallback' means the language model failed for that meeting."
    )


if __name__ == "__main__":
    main()
