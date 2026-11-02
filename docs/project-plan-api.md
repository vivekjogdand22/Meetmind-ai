# Project Plan API (PERT / CPM)

Turns the action items of a meeting into a project schedule.

```
AI action items  ->  plan tasks (a copy)  ->  you add estimates and dependencies
                 ->  PERT expected times  ->  CPM schedule, slack and critical path
```

All durations are in **days** (decimals such as `0.5` are fine). Every endpoint
lives under `/api/meetings/{meetingId}/plan`.

## Ideas in one minute

| Term | Meaning |
|---|---|
| Optimistic / most likely / pessimistic | Your three estimates for a task: best case, normal case, worst case |
| Expected time | `(optimistic + 4 x most likely + pessimistic) / 6` (PERT) |
| Predecessors | Tasks that must finish before this one can start |
| Earliest start / finish | The soonest a task can start / end |
| Latest start / finish | The latest it can start / end without delaying the project |
| Slack | Latest start minus earliest start. Slack of 0 means the task is **critical** |
| Critical path | The chain of critical tasks. Delaying any of them delays the whole project |
| Probability of finishing by a target | Chance that the project ends within `targetDays`, from the spread of the estimates along the critical path |

How the plan behaves:

- **The plan is a copy.** Creating it reads the meeting's action items once. Editing the plan never changes the action items of the meeting.
- **Only your inputs are stored.** The schedule is recalculated on every read, so it can never be out of date.
- **A task can stay a draft.** Leave all three estimates empty until you know them. The schedule appears once *every* task has its estimates.
- **Every saved state is valid.** A change that breaks a rule is refused and nothing is saved.
- **Deleting a meeting deletes its plan** (database-level cascade).

## Endpoints

| Method and path | What it does | Success |
|---|---|---|
| `GET /plan` | The tasks and the schedule. Add `?targetDays=10` to also get the probability of finishing within 10 days | 200 |
| `POST /plan` | Copy the meeting's AI action items into a new plan | 201 |
| `DELETE /plan` | Remove the whole plan | 204 |
| `POST /plan/tasks` | Add a task of your own | 201 |
| `PUT /plan/tasks/{taskId}` | Replace a task's name, owner, estimates and predecessors | 200 |
| `DELETE /plan/tasks/{taskId}` | Remove a task (other tasks stop waiting for it) | 200 |
| `POST /plan/what-if` | Simulate a delayed task. **Nothing is saved** | 200 |

`POST`/`PUT`/`DELETE` of a task and `POST /plan` return the whole updated plan,
so a screen can simply show the answer.

### Task body (`POST /plan/tasks`, `PUT /plan/tasks/{taskId}`)

```json
{
  "name": "Perform integration testing",
  "owner": "Team",
  "optimisticDays": 1,
  "mostLikelyDays": 2,
  "pessimisticDays": 3,
  "predecessorIds": [1, 2]
}
```

- `name` is required (at most 500 characters).
- The three estimates are either **all given or all left out**, each zero or more, in the order optimistic <= most likely <= pessimistic.
- `predecessorIds` are ids of other tasks in the same plan. A task cannot wait for itself, and the links must not form a cycle.
- `PUT` replaces the task completely, so send every field you want to keep.

### What-if body (`POST /plan/what-if`)

```json
{ "taskId": 2, "delayDays": 2 }
```

Answers: the new project duration, how many days late the project is, whether the
task's slack absorbed the delay, and which tasks finish later (for example the
final demonstration).

### Plan response (abbreviated)

```json
{
  "meetingId": 12,
  "readyToSchedule": true,
  "tasksMissingEstimates": [],
  "schedule": {
    "projectDurationDays": 9.0,
    "criticalPath": [2, 3, 4],
    "projectVariance": 2.8889,
    "projectStandardDeviation": 1.6997,
    "targetDays": 10.0,
    "probabilityOfFinishingByTarget": 0.7218
  },
  "tasks": [
    {
      "id": 2,
      "name": "Develop the backend APIs",
      "owner": "Amit",
      "priority": "HIGH",
      "sourceDueDate": "Friday",
      "optimisticDays": 3.0,
      "mostLikelyDays": 5.0,
      "pessimisticDays": 13.0,
      "predecessorIds": [],
      "expectedDays": 6.0,
      "varianceDays": 2.7778,
      "earliestStart": 0.0,
      "earliestFinish": 6.0,
      "latestStart": 0.0,
      "latestFinish": 6.0,
      "slackDays": 0.0,
      "critical": true
    }
  ]
}
```

- While some task has no estimates: `readyToSchedule` is `false`, `schedule` is `null`, the schedule fields of every task are `null`, and `tasksMissingEstimates` lists the task names.
- `priority`, `sourceDueDate` and `evidence` are copied from the AI action item for reference. The schedule does not use them (the AI's due dates are free text such as "next Monday").
- Numbers are rounded to 4 decimal places.

## Errors

Every error has the same shape. `message` is written for people.

```json
{ "status": 400, "error": "Bad Request", "message": "Dependency cycle detected: Build → Test → Design → Build" }
```

| Status | When |
|---|---|
| 400 | Invalid input: a cycle, a task waiting for itself or for an unknown task, partial or unordered estimates, a blank name, a negative number, malformed JSON, a non-numeric id, or no action items to copy |
| 404 | The meeting or the task does not exist |
| 409 | `POST /plan` when the meeting already has a plan (delete it first) |

## Try it

With PostgreSQL and the backend running, this walks through every endpoint and
checks the answers (it creates its own test meeting and removes it at the end):

```bash
python3 docs/plan_api_smoke_test.py
```

Unit tests that need no database:

```bash
cd backend
./mvnw -Dtest='CpmCalculatorTest,PlanServiceTest' test
```

## Limits to know about

- The probability of finishing in time follows the classic PERT approximation: one normal curve along the critical path. When several paths are almost as long as the critical path, it is optimistic.
- Calendar dates are not used yet. Start and finish are counted in days from the project start.
- There is no user login yet, so anyone who can reach the backend can edit any plan.
