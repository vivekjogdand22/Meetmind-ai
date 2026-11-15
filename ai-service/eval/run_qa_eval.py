"""Scores "Ask Your Meeting" on the questions in eval/qa/questions.json.

It needs Ollama running with both models:

    ollama pull nomic-embed-text
    ollama pull qwen3:8b

Run it from the ai-service folder. Two modes:

    python eval/run_qa_eval.py             checks only the search: is the
                                           passage that holds the answer
                                           ranked first, or in the top four?

    python eval/run_qa_eval.py --answers   asks every question for real and
                                           grades the answers

Add --show to print every answer and quote, and --questions FILE to use
another question file, such as eval/qa/heldout.json (questions written
after the prompts were tuned, so they give the honest score).

The grading in --answers mode:

    answered right   an answerable question got an answer with every keyword
                     of the answer key, backed by a verified quote
    missed           an answerable question got "the meeting does not say"
    wrong            an answerable question got an answer without the keywords
    invented         a question with NO answer in the meeting still got one
                     (the worst mistake: the point of the feature is to avoid it)
    blocked          the model tried to answer but could not back it with a
                     real quote, so the quote check held the answer back
"""

import argparse
import json
import statistics
import sys
from pathlib import Path


EVAL_DIR = Path(__file__).parent

sys.path.insert(0, str(EVAL_DIR.parent))
sys.path.insert(0, str(EVAL_DIR))

import question_answering  # noqa: E402
import retrieval  # noqa: E402
from run_eval import has_all_keywords  # noqa: E402


TOP_K = 4


def load_transcripts() -> dict[str, str]:

    transcripts = {}

    for file in (EVAL_DIR / "meetings").glob("*.json"):

        meeting = json.loads(file.read_text())

        transcripts[meeting["name"]] = meeting["transcript"]

    return transcripts


def load_questions(path: Path) -> list[dict]:

    return json.loads(path.read_text())["questions"]


# ============================================================
# MODE 1: THE SEARCH ONLY
# ============================================================

def run_retrieval(transcripts: dict[str, str], questions: list[dict]) -> None:

    print(f"{'meeting':<10}{'rank':>5}{'score':>7}  question")


    ranks: list[int | None] = []

    answerable_scores: list[float] = []

    unanswerable_scores: list[float] = []


    for item in questions:

        try:

            hits = retrieval.search(transcripts[item["meeting"]], item["question"], TOP_K)

        except retrieval.RetrievalError as error:

            sys.exit(f"Cannot search: {error}")


        top_score = hits[0].score if hits else 0.0

        if not item["answerable"]:

            unanswerable_scores.append(top_score)

            print(f"{item['meeting']:<10}{'-':>5}{top_score:>7.2f}  {item['question']}  (no answer exists)")

            continue


        rank = next(
            (
                position
                for position, hit in enumerate(hits)
                if item["evidence"] in hit.passage.text.lower()
            ),
            None,
        )

        ranks.append(rank)

        answerable_scores.append(top_score)

        shown = "miss" if rank is None else str(rank + 1)

        print(f"{item['meeting']:<10}{shown:>5}{top_score:>7.2f}  {item['question']}")


    total = len(ranks)

    first = sum(rank == 0 for rank in ranks)

    top_four = sum(rank is not None for rank in ranks)


    print()
    print(f"Right passage ranked first:      {first}/{total}")
    print(f"Right passage in the top {TOP_K}:       {top_four}/{total}")

    if answerable_scores and unanswerable_scores:

        print(
            f"Best score, answerable questions:    lowest {min(answerable_scores):.2f}, "
            f"median {statistics.median(answerable_scores):.2f}"
        )

        print(
            f"Best score, unanswerable questions:  highest {max(unanswerable_scores):.2f}, "
            f"median {statistics.median(unanswerable_scores):.2f}"
        )

        gap = min(answerable_scores) - max(unanswerable_scores)

        print(f"Gap between the two groups: {gap:+.2f}")

        print(
            "That gap is too small to use as a rule (a different meeting can "
            "close it), so the answer step has to decide whether the meeting "
            "really says it."
            if gap < 0.15
            else "The two groups are clearly separated by their scores."
        )


# ============================================================
# MODE 2: THE ANSWERS
# ============================================================

def grade(item: dict, result: dict) -> str:

    # right / wrong / missed / invented / blocked / correct-refusal

    if not item["answerable"]:

        return "invented" if result["answerable"] else "refused"


    if result["unsupported"]:

        return "blocked"

    if not result["answerable"]:

        return "missed"

    right_words = has_all_keywords(result["answer"], item["answer"])

    grounded = any(item["evidence"] in source["text"].lower() for source in result["sources"])

    return "right" if right_words and grounded else "wrong"


def run_answers(transcripts: dict[str, str], questions: list[dict], show: bool) -> None:

    grades: list[str] = []

    seconds: list[float] = []


    print(f"{'meeting':<10}{'grade':<9}{'sec':>5}  question")


    for item in questions:

        try:

            result = question_answering.answer_question(transcripts[item["meeting"]], item["question"])

        except (retrieval.RetrievalError, question_answering.AnswerError) as error:

            sys.exit(f"Cannot answer: {error}")


        outcome = grade(item, result)

        grades.append(outcome)

        seconds.append(result["seconds"])

        mark = "  " if outcome in ("right", "refused") else "!!"

        print(f"{item['meeting']:<10}{outcome:<9}{result['seconds']:>5.1f}  {mark} {item['question']}")

        if show or mark == "!!":

            print(f"{'':<24}answer: {result['answer']}")

            for source in result["sources"]:

                print(f"{'':<24}quote:  \"{source['quote']}\"")


    answerable = sum(item["answerable"] for item in questions)

    unanswerable = len(questions) - answerable


    print()
    print(f"Answered right:            {grades.count('right')}/{answerable}")
    print(f"Missed (said 'not said'):  {grades.count('missed')}")
    print(f"Wrong answer:              {grades.count('wrong')}")
    print(f"Blocked by the quote check:{grades.count('blocked'):>3}")
    print(f"Correctly refused:         {grades.count('refused')}/{unanswerable}")
    print(f"INVENTED an answer:        {grades.count('invented')}/{unanswerable}   (must be 0)")
    print(f"Seconds per question:      median {statistics.median(seconds):.1f}, slowest {max(seconds):.1f}")


def main() -> None:

    parser = argparse.ArgumentParser(description="Score Ask Your Meeting.")

    parser.add_argument("--answers", action="store_true", help="ask every question for real and grade the answers")

    parser.add_argument("--show", action="store_true", help="print every answer and quote")

    parser.add_argument("--questions", type=Path, default=EVAL_DIR / "qa" / "questions.json", help="question file")

    options = parser.parse_args()


    transcripts = load_transcripts()

    questions = load_questions(options.questions)

    if options.answers:

        run_answers(transcripts, questions, show=options.show)

    else:

        run_retrieval(transcripts, questions)


if __name__ == "__main__":
    main()
