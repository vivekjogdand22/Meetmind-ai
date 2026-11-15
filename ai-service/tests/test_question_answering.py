"""Unit tests for ai-service/question_answering.py and the /ask endpoint.

They need no Ollama and no Whisper: the search and the language model are
replaced by stand-ins, so the tests check our own rules: which quotes are
accepted, when the answer is "the meeting does not say", and how errors
are reported.

Run them from the ai-service folder:

    python -m unittest discover -s tests -v
"""

import sys
import unittest
from pathlib import Path
from unittest import mock

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import question_answering as qa  # noqa: E402
import retrieval  # noqa: E402


TRANSCRIPT = (
    "Alice: We will launch the beta on March 14th. "
    "Bob: I will finish the payment module by Friday. "
    "Carol: The budget is 40,000 dollars, and we can't go over it."
)


def passage(text: str = TRANSCRIPT, index: int = 0, start: int = 0) -> retrieval.Passage:

    return retrieval.Passage(index, text, start, start + len(text))


def reply(answerable=True, answer="The beta launches on March 14th.", evidence=None):

    if evidence is None:

        evidence = [{"excerpt": 1, "quote": "launch the beta on March 14th"}]

    return {"answerable": answerable, "answer": answer, "evidence": evidence}


class FakeResponse:

    def __init__(self, body=None, status=200):

        self.body = body
        self.status_code = status

    def raise_for_status(self):

        if self.status_code >= 400:

            raise requests.HTTPError(f"{self.status_code}")

    def json(self):

        return self.body


def chat_response(content: str) -> FakeResponse:

    return FakeResponse({"message": {"content": content}})


# ============================================================
# LOCATING A QUOTE
# ============================================================

class LocateQuoteTests(unittest.TestCase):

    def test_exact_quote_is_found_with_its_position(self):

        where = qa.locate_quote("launch the beta on March 14th", passage())

        self.assertIsNotNone(where)
        self.assertEqual(TRANSCRIPT[where[0]:where[1]], "launch the beta on March 14th")

    def test_capitals_punctuation_and_spacing_may_differ(self):

        where = qa.locate_quote("  LAUNCH the beta,   on march 14th!! ", passage())

        self.assertIsNotNone(where)
        self.assertEqual(TRANSCRIPT[where[0]:where[1]], "launch the beta on March 14th")

    def test_a_number_with_a_comma_is_found(self):

        where = qa.locate_quote("The budget is 40,000 dollars", passage())

        self.assertIsNotNone(where)
        self.assertEqual(TRANSCRIPT[where[0]:where[1]], "The budget is 40,000 dollars")

    def test_curly_apostrophe_matches_a_straight_one(self):

        self.assertIsNotNone(qa.locate_quote("we can’t go over it", passage()))

    def test_an_invented_quote_is_rejected(self):

        self.assertIsNone(qa.locate_quote("launch the beta on April 2nd", passage()))

    def test_the_same_words_in_another_order_are_rejected(self):

        self.assertIsNone(qa.locate_quote("beta the launch", passage()))

    def test_a_quote_cut_in_the_middle_of_a_word_is_rejected(self):

        self.assertIsNone(qa.locate_quote("launch the be", passage()))
        self.assertIsNone(qa.locate_quote("aunch the beta", passage()))

    def test_a_quote_cannot_start_or_end_inside_a_number_a_hyphen_or_a_contraction(self):

        cases = [
            ("500 dollars a year", "The licence costs 1,500 dollars a year."),
            ("it costs 1", "The licence says it costs 1,500 today."),
            ("5 million", "The budget is 2.5 million for now."),
            ("30 in the morning", "We meet at 10:30 in the morning."),
            ("blocking issue for the release", "It is a non-blocking issue for the release."),
            ("Priya said she can", "Priya said she can't make it."),
        ]

        for quote, text in cases:

            self.assertIsNone(qa.locate_quote(quote, passage(text)), quote)

    def test_real_quotes_next_to_punctuation_still_match(self):

        for quote, text in [
            ("1,500 dollars a year", "The licence costs 1,500 dollars a year."),
            ("we can't go over it", "Carol said: we can't go over it, sadly."),
            ("going to yeah", "I was going to- yeah, that works."),
            ("on March 14th", "Launch on March 14th. Bob agreed."),
        ]:

            self.assertIsNotNone(qa.locate_quote(quote, passage(text)), quote)

    def test_one_word_is_too_short_to_prove_anything(self):

        self.assertIsNone(qa.locate_quote("beta", passage()))

    def test_a_quote_without_words_is_rejected(self):

        self.assertIsNone(qa.locate_quote("... !!", passage()))

    def test_a_very_long_quote_is_rejected(self):

        long_text = " ".join(f"word{n}" for n in range(qa.MAX_QUOTE_WORDS + 1))

        self.assertIsNone(qa.locate_quote(long_text, passage(long_text)))

    def test_positions_are_relative_to_the_whole_transcript(self):

        offset = 1000

        where = qa.locate_quote("finish the payment module", passage(start=offset))

        self.assertIsNotNone(where)

        inside = TRANSCRIPT.lower().index("finish the payment module")

        self.assertEqual(where, (offset + inside, offset + inside + len("finish the payment module")))


# ============================================================
# NUMBERS IN AN ANSWER
# ============================================================

class NumberCheckTests(unittest.TestCase):

    def test_digits_words_and_ordinals_are_the_same_number(self):

        for text in ("on the 20th", "on the twentieth", "on day 20", "twenty"):

            self.assertEqual(qa.numbers_in(text), {"20"}, text)

    def test_compound_number_words(self):

        self.assertEqual(qa.numbers_in("the twenty-second"), {"22"})
        self.assertEqual(qa.numbers_in("twenty five days"), {"25"})

    def test_commas_and_decimals(self):

        self.assertEqual(qa.numbers_in("40,000 dollars"), {"40000"})
        self.assertEqual(qa.numbers_in("2.5 weeks."), {"2.5"})

    def test_ambiguous_words_are_skipped_only_when_asked(self):

        self.assertEqual(qa.numbers_in("No one did the first step", skip_ambiguous=True), set())
        self.assertEqual(qa.numbers_in("one week"), {"1"})

    def test_scale_words_change_the_number(self):

        self.assertEqual(qa.numbers_in("40K"), {"40000"})
        self.assertEqual(qa.numbers_in("forty thousand dollars"), {"40000"})
        self.assertEqual(qa.numbers_in("$1.5 million"), {"1500000"})
        self.assertEqual(qa.numbers_in("two hundred licenses"), {"200"})
        self.assertNotEqual(qa.numbers_in("40 million"), qa.numbers_in("40K"))

    def test_a_wrong_magnitude_is_not_backed(self):

        self.assertFalse(qa.numbers_backed("The budget is 40 million.", [{"quote": "we have a 40K budget"}]))
        self.assertTrue(qa.numbers_backed("The budget is 40,000 dollars.", [{"quote": "a forty thousand dollar budget"}]))

    def test_one_week_is_checked_but_no_one_is_not(self):

        self.assertEqual(qa.numbers_in("it takes one week", skip_ambiguous=True), {"1"})
        self.assertEqual(qa.numbers_in("it takes a week"), {"1"})
        self.assertEqual(qa.numbers_in("no one is writing it", skip_ambiguous=True), set())
        self.assertFalse(qa.numbers_backed("It takes one week.", [{"quote": "it takes about two weeks"}]))

    def test_a_number_missing_from_the_quotes_is_not_backed(self):

        # the case the evaluation found: the deadline was quoted, the
        # "three days" were made up
        sources = [{"quote": "Tara will design the screens in Figma, and that should be done by next Wednesday."}]

        self.assertFalse(qa.numbers_backed("Tara needs three days for the Figma designs.", sources))

    def test_a_number_in_the_quotes_is_backed_in_any_spelling(self):

        sources = [{"quote": "we want that to happen on the twentieth"}]

        self.assertTrue(qa.numbers_backed("Deployment is on the 20th.", sources))

    def test_an_answer_without_numbers_needs_none(self):

        self.assertTrue(qa.numbers_backed("Meera owns it.", [{"quote": "Meera, you own the Stripe integration"}]))


# ============================================================
# THE CALL TO THE LANGUAGE MODEL
# ============================================================

class AskModelTests(unittest.TestCase):

    def test_a_good_answer_is_returned_as_a_dict(self):

        with mock.patch("question_answering.requests.post", return_value=chat_response('{"answerable": true}')):

            self.assertEqual(qa.ask_model("When?", [passage()]), {"answerable": True})

    def test_request_turns_thinking_off_and_keeps_the_temperature_low(self):

        with mock.patch("question_answering.requests.post", return_value=chat_response("{}")) as post:

            qa.ask_model("When is the launch?", [passage()])

        sent = post.call_args.kwargs["json"]

        self.assertIs(sent["think"], False)
        self.assertIs(sent["stream"], False)
        self.assertEqual(sent["options"]["temperature"], 0.1)
        self.assertEqual(sent["format"], qa.ANSWER_SCHEMA)
        self.assertEqual(sent["messages"][0]["content"], qa.SYSTEM_PROMPT)

    def test_the_prompt_numbers_the_excerpts_and_ends_with_the_question(self):

        first, second = passage("First excerpt text.", 0), passage("Second excerpt text.", 1)

        message = qa.build_user_message("Who?", [first, second])

        self.assertIn("[1]\nFirst excerpt text.", message)
        self.assertIn("[2]\nSecond excerpt text.", message)
        self.assertTrue(message.endswith("QUESTION: Who?"))

    def test_ollama_down_gives_a_clear_error(self):

        with mock.patch("question_answering.requests.post", side_effect=requests.ConnectionError()):

            with self.assertRaisesRegex(qa.AnswerError, "Ollama is not reachable"):

                qa.ask_model("When?", [passage()])

    def test_a_missing_model_tells_the_user_what_to_pull(self):

        with mock.patch("question_answering.requests.post", return_value=FakeResponse(status=404)):

            with self.assertRaisesRegex(qa.AnswerError, "ollama pull"):

                qa.ask_model("When?", [passage()])

    def test_text_that_is_not_json_is_an_error(self):

        with mock.patch("question_answering.requests.post", return_value=chat_response("Sure! It is March.")):

            with self.assertRaisesRegex(qa.AnswerError, "could not be read"):

                qa.ask_model("When?", [passage()])

    def test_json_that_is_not_an_object_is_an_error(self):

        with mock.patch("question_answering.requests.post", return_value=chat_response("[1, 2]")):

            with self.assertRaisesRegex(qa.AnswerError, "could not be read"):

                qa.ask_model("When?", [passage()])

    def test_a_server_error_is_an_error(self):

        with mock.patch("question_answering.requests.post", return_value=FakeResponse(status=500)):

            with self.assertRaisesRegex(qa.AnswerError, "could not be read"):

                qa.ask_model("When?", [passage()])


class SecondCheckTests(unittest.TestCase):

    SOURCES = [{"quote": "we are going with PostgreSQL and not MongoDB, that is final"}]

    def run_check(self, content):

        with mock.patch("question_answering.requests.post", return_value=chat_response(content)) as post:

            supported = qa.answer_is_supported("Why PostgreSQL?", "Because it is final.", self.SOURCES)

        return supported, post.call_args.kwargs["json"]

    def test_only_a_real_true_counts_as_supported(self):

        self.assertTrue(self.run_check('{"supported": true}')[0])

        for content in ('{"supported": false}', '{"supported": "true"}', "{}"):

            self.assertFalse(self.run_check(content)[0], content)

    def test_the_check_sends_the_question_the_answer_and_the_quotes(self):

        _, sent = self.run_check('{"supported": true}')

        message = sent["messages"][1]["content"]

        self.assertEqual(sent["messages"][0]["content"], qa.CHECK_PROMPT)
        self.assertEqual(sent["format"], qa.CHECK_SCHEMA)
        self.assertIs(sent["think"], False)
        self.assertIn("QUESTION: Why PostgreSQL?", message)
        self.assertIn("PROPOSED ANSWER: Because it is final.", message)
        self.assertIn(">>>we are going with PostgreSQL and not MongoDB, that is final<<<", message)

    def test_the_check_prefers_the_context_when_there_is_one(self):

        sources = [{"quote": "blocked on credentials", "context": "Sneha, >>>blocked on credentials<<<, right? Yes, no access."}]

        with mock.patch("question_answering.requests.post", return_value=chat_response('{"supported": true}')) as post:

            qa.answer_is_supported("Why?", "No access.", sources)

        self.assertIn("Yes, no access.", post.call_args.kwargs["json"]["messages"][1]["content"])

    def test_ollama_down_during_the_check_is_a_clear_error(self):

        with mock.patch("question_answering.requests.post", side_effect=requests.ConnectionError()):

            with self.assertRaisesRegex(qa.AnswerError, "Ollama is not reachable"):

                qa.answer_is_supported("Why?", "Because.", self.SOURCES)


# ============================================================
# CHECKING THE EVIDENCE
# ============================================================

class VerifiedSourcesTests(unittest.TestCase):

    def test_a_real_quote_becomes_a_source_with_its_position_and_score(self):

        sources = qa.verified_sources(reply(), [passage()], {0: 0.8123})

        self.assertEqual(len(sources), 1)

        source = sources[0]

        self.assertEqual(source["passage"], 0)
        self.assertEqual(source["text"], TRANSCRIPT)
        self.assertEqual(source["score"], 0.812)
        self.assertEqual(TRANSCRIPT[source["quoteStart"]:source["quoteEnd"]], "launch the beta on March 14th")

    def test_the_shown_quote_is_the_transcripts_own_words(self):

        evidence = [{"excerpt": 1, "quote": "  LAUNCH the beta,   on march 14th!! "}]

        sources = qa.verified_sources(reply(evidence=evidence), [passage()], {})

        self.assertEqual(sources[0]["quote"], "launch the beta on March 14th")

    def test_a_source_carries_the_context_around_its_quote(self):

        evidence = [{"excerpt": 1, "quote": "finish the payment module by Friday"}]

        sources = qa.verified_sources(reply(evidence=evidence), [passage()], {})

        context = sources[0]["context"]

        self.assertIn(">>>finish the payment module by Friday<<<", context)
        self.assertIn("Alice: We will launch the beta on March 14th.", context)  # the sentence before
        self.assertIn("Carol: The budget is 40,000 dollars", context)  # the sentence after

    def test_an_invented_quote_is_dropped(self):

        evidence = [{"excerpt": 1, "quote": "we will launch the beta in June"}]

        self.assertEqual(qa.verified_sources(reply(evidence=evidence), [passage()], {}), [])

    def test_a_wrong_excerpt_number_is_forgiven_when_the_quote_is_real(self):

        other = passage("Nothing useful here at all.", 0)
        real = passage(TRANSCRIPT, 1)

        evidence = [{"excerpt": 1, "quote": "finish the payment module by Friday"}]

        sources = qa.verified_sources(reply(evidence=evidence), [other, real], {})

        self.assertEqual([source["passage"] for source in sources], [1])

    def test_an_excerpt_number_out_of_range_does_not_crash(self):

        evidence = [{"excerpt": 9, "quote": "finish the payment module by Friday"}]

        self.assertEqual(len(qa.verified_sources(reply(evidence=evidence), [passage()], {})), 1)

    def test_the_same_place_is_listed_once(self):

        evidence = [
            {"excerpt": 1, "quote": "launch the beta on March 14th"},
            {"excerpt": 1, "quote": "Launch the beta on march 14th."},
        ]

        self.assertEqual(len(qa.verified_sources(reply(evidence=evidence), [passage()], {})), 1)

    def test_good_and_bad_quotes_can_be_mixed(self):

        evidence = [
            {"excerpt": 1, "quote": "nothing like this was said"},
            {"excerpt": 1, "quote": "finish the payment module by Friday"},
        ]

        sources = qa.verified_sources(reply(evidence=evidence), [passage()], {})

        self.assertEqual(len(sources), 1)
        self.assertIn("payment module", sources[0]["quote"])

    def test_evidence_that_is_not_a_list_gives_nothing(self):

        for bad in (None, "launch the beta", {"quote": "launch the beta"}, 7):

            self.assertEqual(qa.verified_sources({"evidence": bad}, [passage()], {}), [])

        self.assertEqual(qa.verified_sources({}, [passage()], {}), [])

    def test_malformed_items_are_skipped(self):

        evidence = ["launch the beta", None, {"excerpt": 1}, {"excerpt": 1, "quote": 5}]

        self.assertEqual(qa.verified_sources(reply(evidence=evidence), [passage()], {}), [])


# ============================================================
# THE WHOLE ANSWER
# ============================================================

def hits_for(*passages: retrieval.Passage) -> list[retrieval.Hit]:

    return [retrieval.Hit(p, 0.9 - 0.1 * n) for n, p in enumerate(passages)]


class AnswerQuestionTests(unittest.TestCase):

    def ask(self, model_reply, hits=None, question="When is the beta launch?", supported=True):

        hits = hits_for(passage()) if hits is None else hits

        with mock.patch("question_answering.retrieval.search", return_value=hits), \
                mock.patch("question_answering.ask_model", return_value=model_reply) as model, \
                mock.patch("question_answering.answer_is_supported", return_value=supported) as check:

            self.check = check

            return qa.answer_question(TRANSCRIPT, question), model

    def test_an_answer_the_second_check_rejects_is_not_shown(self):

        result, _ = self.ask(reply(), supported=False)

        self.assertFalse(result["answerable"])
        self.assertTrue(result["unsupported"])
        self.assertEqual(result["answer"], qa.UNCONFIRMED)

    def test_the_second_check_gets_the_question_answer_and_verified_quotes(self):

        self.ask(reply(), question="  When is the beta launch?  ")

        question, answer, sources = self.check.call_args.args

        self.assertEqual(question, "When is the beta launch?")
        self.assertEqual(answer, "The beta launches on March 14th.")
        self.assertEqual([source["quote"] for source in sources], ["launch the beta on March 14th"])

    def test_the_second_check_is_skipped_when_there_is_nothing_to_check(self):

        self.ask(reply(answerable=False, evidence=[]))
        self.check.assert_not_called()

        self.ask(reply(evidence=[{"excerpt": 1, "quote": "never said this at all"}]))
        self.check.assert_not_called()

        self.ask(reply(answer="The beta launches on March 15th."))
        self.check.assert_not_called()

    def test_a_supported_answer_is_returned_with_its_sources(self):

        result, _ = self.ask(reply())

        self.assertTrue(result["answerable"])
        self.assertFalse(result["unsupported"])
        self.assertEqual(result["answer"], "The beta launches on March 14th.")
        self.assertEqual(len(result["sources"]), 1)
        self.assertEqual(result["model"], qa.OLLAMA_MODEL)

    def test_the_model_saying_it_is_not_in_the_meeting_is_passed_on(self):

        result, _ = self.ask(reply(answerable=False, answer="It was never mentioned.", evidence=[]))

        self.assertFalse(result["answerable"])
        self.assertFalse(result["unsupported"])
        self.assertEqual(result["answer"], qa.NOT_SAID)
        self.assertEqual(result["sources"], [])

    def test_an_answer_with_an_invented_quote_is_not_shown(self):

        invented = [{"excerpt": 1, "quote": "the capital of France is Paris"}]

        result, _ = self.ask(reply(answer="Paris.", evidence=invented))

        self.assertFalse(result["answerable"])
        self.assertTrue(result["unsupported"])
        self.assertEqual(result["answer"], qa.UNCONFIRMED)
        self.assertEqual(result["sources"], [])

    def test_an_answer_with_a_number_the_quote_does_not_hold_is_not_shown(self):

        result, _ = self.ask(reply(answer="The beta launches on March 15th."))

        self.assertFalse(result["answerable"])
        self.assertTrue(result["unsupported"])
        self.assertEqual(result["answer"], qa.UNCONFIRMED)

    def test_an_answer_with_no_evidence_at_all_is_not_shown(self):

        result, _ = self.ask(reply(evidence=[]))

        self.assertFalse(result["answerable"])
        self.assertTrue(result["unsupported"])

    def test_a_blank_answer_is_not_shown(self):

        for blank in ("", "   ", None, 5):

            result, _ = self.ask(reply(answer=blank))

            self.assertFalse(result["answerable"], blank)
            self.assertTrue(result["unsupported"], blank)

    def test_only_a_real_true_counts_as_answerable(self):

        for claim in ("true", "yes", 1, None):

            result, _ = self.ask(reply(answerable=claim))

            self.assertFalse(result["answerable"], claim)
            self.assertFalse(result["unsupported"], claim)

    def test_no_passages_means_no_question_to_the_model(self):

        result, model = self.ask(reply(), hits=[])

        self.assertFalse(result["answerable"])
        self.assertEqual(result["answer"], qa.NOT_SAID)

        model.assert_not_called()

    def test_the_model_reads_the_passages_in_the_order_they_were_said(self):

        early, middle, late = passage("Early.", 0), passage("Middle.", 3), passage("Late.", 7)

        # the search returns them best first, not in transcript order
        _, model = self.ask(reply(), hits=hits_for(late, early, middle))

        sent = model.call_args.args[1]

        self.assertEqual([p.index for p in sent], [0, 3, 7])

    def test_the_score_of_each_passage_is_reported(self):

        result, _ = self.ask(reply(), hits=[retrieval.Hit(passage(), 0.7349)])

        self.assertEqual(result["sources"][0]["score"], 0.735)

    def test_a_broad_question_is_flagged(self):

        broad, _ = self.ask(reply(), question="Summarize the whole meeting")
        narrow, _ = self.ask(reply(), question="When is the beta launch?")

        self.assertTrue(broad["broad"])
        self.assertFalse(narrow["broad"])

    def test_the_context_is_not_sent_to_the_user(self):

        result, _ = self.ask(reply())

        self.assertNotIn("context", result["sources"][0])

    def test_a_blank_question_is_refused(self):

        with self.assertRaises(ValueError):

            qa.answer_question(TRANSCRIPT, "   ")

    def test_the_question_is_trimmed_before_it_is_used(self):

        with mock.patch("question_answering.retrieval.search", return_value=[]) as search:

            qa.answer_question(TRANSCRIPT, "  When?  ")

        self.assertEqual(search.call_args.args[1], "When?")

    def test_search_errors_are_passed_on(self):

        with mock.patch("question_answering.retrieval.search", side_effect=retrieval.RetrievalError("down")):

            with self.assertRaises(retrieval.RetrievalError):

                qa.answer_question(TRANSCRIPT, "When?")

    def test_model_errors_are_passed_on(self):

        with mock.patch("question_answering.retrieval.search", return_value=hits_for(passage())), \
                mock.patch("question_answering.ask_model", side_effect=qa.AnswerError("no model")):

            with self.assertRaises(qa.AnswerError):

                qa.answer_question(TRANSCRIPT, "When?")


# ============================================================
# THE HTTP ENDPOINT
#
# main.py loads the Whisper model when it is imported. That is slow and
# needs a download, so a stand-in is used for it here.
# ============================================================

def load_main():

    with mock.patch.dict(sys.modules, {"faster_whisper": mock.MagicMock()}):

        import main

    return main


class AskEndpointTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):

        from fastapi.testclient import TestClient

        cls.main = load_main()

        cls.client = TestClient(cls.main.app, raise_server_exceptions=False)

    def post(self, body):

        return self.client.post("/ask", json=body)

    def test_a_good_question_passes_through(self):

        answer = {"answer": "March 14th", "answerable": True, "unsupported": False, "sources": []}

        with mock.patch.object(self.main, "answer_question", return_value=answer) as ask:

            response = self.post({"transcript": f"  {TRANSCRIPT}  ", "question": "  When?  "})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), answer)

        ask.assert_called_once_with(TRANSCRIPT, "When?")

    def test_an_empty_transcript_is_a_400(self):

        response = self.post({"transcript": "   ", "question": "When?"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "Transcript cannot be empty")

    def test_an_empty_question_is_a_400(self):

        response = self.post({"transcript": TRANSCRIPT, "question": "   "})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "Question cannot be empty")

    def test_a_question_over_the_limit_is_a_400(self):

        response = self.post({"transcript": TRANSCRIPT, "question": "x" * (qa.MAX_QUESTION_CHARS + 1)})

        self.assertEqual(response.status_code, 400)
        self.assertIn("too long", response.json()["detail"])

    def test_a_question_exactly_at_the_limit_is_accepted(self):

        with mock.patch.object(self.main, "answer_question", return_value={"answer": "ok"}):

            response = self.post({"transcript": TRANSCRIPT, "question": "x" * qa.MAX_QUESTION_CHARS})

        self.assertEqual(response.status_code, 200)

    def test_missing_fields_are_a_422(self):

        self.assertEqual(self.post({"transcript": TRANSCRIPT}).status_code, 422)
        self.assertEqual(self.post({"question": "When?"}).status_code, 422)

    def test_ollama_problems_are_a_503_with_the_reason(self):

        for error in (retrieval.RetrievalError("Run: ollama pull nomic-embed-text"), qa.AnswerError("Ollama is not reachable")):

            with mock.patch.object(self.main, "answer_question", side_effect=error):

                response = self.post({"transcript": TRANSCRIPT, "question": "When?"})

            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()["detail"], str(error))

    def test_an_unexpected_failure_is_a_500_with_the_reason(self):

        with mock.patch.object(self.main, "answer_question", side_effect=RuntimeError("boom")):

            response = self.post({"transcript": TRANSCRIPT, "question": "When?"})

        self.assertEqual(response.status_code, 500)
        self.assertIn("boom", response.json()["detail"])

    def test_health_still_works(self):

        self.assertEqual(self.client.get("/health").json()["status"], "UP")


if __name__ == "__main__":
    unittest.main()
