"""Walks through the whole project-plan API of a running backend and checks
every answer. It creates its own test meeting and deletes it again at the end.

Start PostgreSQL and the backend first, then from the project folder:

    python3 docs/plan_api_smoke_test.py

It uses http://localhost:8080 unless you give another address:

    python3 docs/plan_api_smoke_test.py http://localhost:8080

Only the Python standard library is needed.
"""

import json
import sys
import urllib.error
import urllib.request


BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")

ACTION_ITEMS = [
    {"task": "Complete the frontend design", "owner": "Vivek", "dueDate": "Wednesday",
     "priority": "MEDIUM", "evidence": "Vivek will complete the frontend design by Wednesday."},
    {"task": "Develop the backend APIs", "owner": "Amit", "dueDate": "Friday",
     "priority": "HIGH", "evidence": "Amit will develop the backend APIs by Friday."},
    {"task": "Perform integration testing", "owner": "Team", "dueDate": "next Monday",
     "priority": "MEDIUM", "evidence": "The team will perform integration testing next Monday."},
    {"task": "Give the final demonstration", "owner": "Team", "dueDate": "Thursday",
     "priority": "MEDIUM", "evidence": "The team will give the final demonstration on Thursday."},
]

checks = 0
failures: list[str] = []


# ============================================================
# HELPERS
# ============================================================

def call(method, path, body=None, raw_body=None):

    data = raw_body if raw_body is not None else (
        json.dumps(body).encode() if body is not None else None
    )

    request = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )

    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)

    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, text


def check(label, condition, detail=""):

    global checks

    checks += 1

    if condition:
        print(f"  ok    {label}")
    else:
        failures.append(label)
        print(f"  FAIL  {label}  {detail}")


def estimates(name, o, m, p, predecessors=()):

    return {
        "name": name,
        "optimisticDays": o,
        "mostLikelyDays": m,
        "pessimisticDays": p,
        "predecessorIds": list(predecessors),
    }


def message_of(body):

    return body.get("message", "") if isinstance(body, dict) else str(body)


# ============================================================
# THE WALK-THROUGH
# ============================================================

def main():

    print(f"Project-plan API smoke test against {BASE}\n")

    print("Setup")
    status, meeting = call("POST", "/api/meetings", {
        "title": "Plan API smoke test",
        "transcript": "test",
        "actionItems": json.dumps(ACTION_ITEMS),
        "status": "COMPLETED",
    })
    check("test meeting created", status == 201 and meeting.get("id"), f"(status {status})")
    meeting_id = meeting["id"]
    plan = f"/api/meetings/{meeting_id}/plan"


    print("\nAn empty plan, then copying the AI action items")
    status, body = call("GET", plan)
    check("new meeting has an empty plan", status == 200 and body["tasks"] == [] and not body["readyToSchedule"])

    status, body = call("POST", plan)
    check("POST plan copies the action items (201)", status == 201 and len(body["tasks"]) == 4, f"(status {status})")
    names = [task["name"] for task in body["tasks"]]
    check("task names come from the action items", names[1] == "Develop the backend APIs")
    check("draft plan has no schedule yet", body["readyToSchedule"] is False and body["schedule"] is None)
    check("all four tasks are listed as missing estimates", len(body["tasksMissingEstimates"]) == 4)
    ids = [task["id"] for task in body["tasks"]]
    design, backend, testing, demo = ids

    status, body = call("POST", plan)
    check("creating the plan twice is a conflict (409)", status == 409, f"(status {status})")


    print("\nEntering estimates and dependencies")
    call("PUT", f"{plan}/tasks/{design}", estimates("Complete the frontend design", 2, 4, 6))
    call("PUT", f"{plan}/tasks/{backend}", estimates("Develop the backend APIs", 3, 5, 13))
    status, body = call("PUT", f"{plan}/tasks/{testing}",
                        estimates("Perform integration testing", 1, 2, 3, [design, backend]))
    check("plan is still a draft while one task lacks estimates", status == 200 and not body["readyToSchedule"])

    status, body = call("PUT", f"{plan}/tasks/{demo}",
                        estimates("Give the final demonstration", 1, 1, 1, [testing]))
    check("plan is ready once every task has estimates", status == 200 and body["readyToSchedule"])
    check("project duration is 9 days", body["schedule"]["projectDurationDays"] == 9.0)
    check("critical path is backend -> testing -> demo", body["schedule"]["criticalPath"] == [backend, testing, demo])
    by_id = {task["id"]: task for task in body["tasks"]}
    check("frontend design has 2 days of slack", by_id[design]["slackDays"] == 2.0 and by_id[design]["critical"] is False)
    check("backend APIs expected time is 6 days", by_id[backend]["expectedDays"] == 6.0)
    check("final demonstration runs from day 8 to 9",
          by_id[demo]["earliestStart"] == 8.0 and by_id[demo]["earliestFinish"] == 9.0)

    status, body = call("GET", f"{plan}?targetDays=10")
    chance = body["schedule"]["probabilityOfFinishingByTarget"]
    check("chance of finishing within 10 days is about 72%", status == 200 and abs(chance - 0.722) < 0.01, f"(got {chance})")


    print("\nBad requests are refused and change nothing")
    status, body = call("PUT", f"{plan}/tasks/{design}",
                        estimates("Complete the frontend design", 2, 4, 6, [demo]))
    check("a dependency cycle is refused (400)", status == 400 and "cycle" in message_of(body), f"(status {status}: {message_of(body)})")
    status, body = call("GET", plan)
    by_id = {task["id"]: task for task in body["tasks"]}
    check("the refused change was rolled back", by_id[design]["predecessorIds"] == [])

    status, body = call("PUT", f"{plan}/tasks/{design}", estimates("Complete the frontend design", 2, None, None))
    check("partial estimates are refused (400)", status == 400 and "all three" in message_of(body))

    status, body = call("PUT", f"{plan}/tasks/{design}", estimates("Complete the frontend design", 5, 3, 8))
    check("unordered estimates are refused (400)", status == 400 and "optimistic" in message_of(body))

    status, body = call("PUT", f"{plan}/tasks/{design}", estimates("   ", 1, 2, 3))
    check("a blank name is refused (400)", status == 400 and "name" in message_of(body), f"(status {status}: {message_of(body)})")

    status, body = call("PUT", f"{plan}/tasks/{design}", estimates("Complete the frontend design", -1, 2, 3))
    check("a negative estimate is refused (400)", status == 400)

    status, body = call("PUT", f"{plan}/tasks/{design}", estimates("Complete the frontend design", 1, 2, 3, [999999]))
    check("an unknown predecessor is refused (400)", status == 400 and "unknown" in message_of(body))

    status, body = call("PUT", f"{plan}/tasks/{design}", raw_body=b"{ this is not json")
    check("malformed JSON is refused (400)", status == 400)

    status, body = call("DELETE", f"{plan}/tasks/abc")
    check("a non-numeric task id is refused (400)", status == 400, f"(status {status})")

    status, body = call("PUT", f"{plan}/tasks/999999", estimates("Nope", 1, 2, 3))
    check("editing an unknown task is 404", status == 404)

    status, body = call("GET", "/api/meetings/999999/plan")
    check("a plan of an unknown meeting is 404", status == 404)


    print("\nAdding and removing a task")
    status, body = call("POST", f"{plan}/tasks", estimates("Write the final report", 1, 2, 3, [demo]))
    check("adding a task (201)", status == 201 and len(body["tasks"]) == 5, f"(status {status})")
    check("the report extends the project to 11 days", body["schedule"]["projectDurationDays"] == 11.0)
    report = body["tasks"][-1]["id"]

    status, body = call("DELETE", f"{plan}/tasks/{report}")
    check("removing the task (200)", status == 200 and len(body["tasks"]) == 4)
    check("the project is back to 9 days", body["schedule"]["projectDurationDays"] == 9.0)


    print("\nWhat if a task is delayed? (nothing is saved)")
    status, body = call("POST", f"{plan}/what-if", {"taskId": backend, "delayDays": 2})
    check("delaying the backend 2 days moves the project to 11", status == 200 and body["newDurationDays"] == 11.0, f"(status {status})")
    check("the project is 2 days late and not absorbed", body["projectDelayDays"] == 2.0 and body["absorbedBySlack"] is False)
    demo_shift = next(shift for shift in body["shiftedTasks"] if shift["id"] == demo)
    check("the final demonstration moves from day 9 to 11", demo_shift["baselineFinish"] == 9.0 and demo_shift["newFinish"] == 11.0)

    status, body = call("POST", f"{plan}/what-if", {"taskId": design, "delayDays": 2})
    check("a 2-day delay of the design is absorbed by its slack", body["absorbedBySlack"] is True and body["newDurationDays"] == 9.0)

    status, body = call("POST", f"{plan}/what-if", {"taskId": 999999, "delayDays": 1})
    check("what-if for an unknown task is 404", status == 404)

    status, body = call("POST", f"{plan}/what-if", {"taskId": design, "delayDays": -1})
    check("a negative delay is refused (400)", status == 400)

    status, body = call("GET", plan)
    check("what-if did not change the saved plan", body["schedule"]["projectDurationDays"] == 9.0)


    print("\nRemoving a task that others depend on")
    status, body = call("DELETE", f"{plan}/tasks/{backend}")
    by_id = {task["id"]: task for task in body["tasks"]}
    check("the other tasks stop waiting for it", status == 200 and by_id[testing]["predecessorIds"] == [design])
    check("the schedule is recalculated (design 4 + testing 2 + demo 1 = 7)", body["schedule"]["projectDurationDays"] == 7.0)


    print("\nDeleting the plan, then the meeting")
    status, body = call("DELETE", plan)
    check("DELETE plan (204)", status == 204, f"(status {status})")
    status, body = call("GET", plan)
    check("the plan is empty again", status == 200 and body["tasks"] == [])

    call("POST", plan)
    status, body = call("DELETE", f"/api/meetings/{meeting_id}")
    check("deleting a meeting that has a plan works (204)", status == 204, f"(status {status})")
    status, body = call("GET", plan)
    check("the deleted meeting's plan is gone (404)", status == 404)


    print(f"\n{checks} checks, {len(failures)} failed")

    if failures:
        print("Failed: " + "; ".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
