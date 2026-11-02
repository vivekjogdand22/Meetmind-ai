"""Unit tests for ai-service/meeting_analysis.py.

They need no Ollama and no internet: the call to Ollama is replaced by a fake.

Run them from the ai-service folder:

    python -m unittest discover -s tests -v
"""

import importlib
import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import meeting_analysis as ma  # noqa: E402


def fake_ollama_answer(analysis: dict) -> mock.Mock:

    response = mock.Mock()

    response.json.return_value = {
        "message": {"content": json.dumps(analysis)}
    }

    return response


GOOD_ANSWER = {
    "summary": "A short meeting.",
    "keyDecisions": ["Use React"],
    "actionItems": [
        {
            "task": "Complete the design",
            "owner": "Vivek",
            "dueDate": "next Monday",
            "priority": "MEDIUM",
            "evidence": "Vivek will complete the design next Monday.",
        }
    ],
}


class CleanOptionalTextTests(unittest.TestCase):

    def test_placeholder_words_become_none(self):

        for text in ["null", "NULL", " None ", "n/a", "N/A", "unknown", "TBD", ""]:
            self.assertIsNone(ma.clean_optional_text(text), text)

    def test_real_text_is_kept_and_trimmed(self):

        self.assertEqual(ma.clean_optional_text("  next Monday "), "next Monday")

    def test_values_that_are_not_text_become_none(self):

        self.assertIsNone(ma.clean_optional_text(None))
        self.assertIsNone(ma.clean_optional_text(5))


class ValidateAnalysisTests(unittest.TestCase):

    def analysis_with(self, item: dict) -> dict:

        return {"summary": "ok", "keyDecisions": [], "actionItems": [item]}

    def test_null_text_in_owner_and_due_date_is_cleaned(self):

        item = {"task": "A", "owner": "null", "dueDate": "null", "priority": "LOW", "evidence": "x"}

        cleaned = ma.validate_analysis(self.analysis_with(item))["actionItems"][0]

        self.assertEqual(cleaned["owner"], "Unassigned")
        self.assertIsNone(cleaned["dueDate"])

    def test_priority_ignores_case_and_unknown_values_become_medium(self):

        for given, expected in [("high", "HIGH"), (" Low ", "LOW"), ("urgent", "MEDIUM"), (None, "MEDIUM")]:

            item = {"task": "A", "owner": "Sam", "dueDate": None, "priority": given, "evidence": "x"}

            cleaned = ma.validate_analysis(self.analysis_with(item))["actionItems"][0]

            self.assertEqual(cleaned["priority"], expected, given)

    def test_due_dates_are_kept_as_spoken(self):

        item = {"task": "A", "owner": "Sam", "dueDate": " next Monday ", "priority": "LOW", "evidence": "x"}

        cleaned = ma.validate_analysis(self.analysis_with(item))["actionItems"][0]

        self.assertEqual(cleaned["dueDate"], "next Monday")

    def test_a_missing_summary_is_rejected(self):

        with self.assertRaises(ValueError):
            ma.validate_analysis({"keyDecisions": [], "actionItems": []})


class ContextSizeTests(unittest.TestCase):

    def test_a_short_meeting_uses_the_smallest_window(self):

        self.assertEqual(ma.choose_context_size("Vivek will finish the design."), 4096)

    def test_a_long_meeting_gets_a_bigger_window(self):

        thirty_minutes = "word " * 4500

        self.assertEqual(ma.choose_context_size(thirty_minutes), 16384)

    def test_the_window_never_exceeds_the_largest_size_and_warns(self):

        printed = io.StringIO()

        with redirect_stdout(printed):
            size = ma.choose_context_size("word " * 60000)

        self.assertEqual(size, 32768)
        self.assertIn("may be cut off", printed.getvalue())

    def test_text_in_other_scripts_is_not_underestimated(self):

        # Devanagari needs about 3 bytes per character in UTF-8
        marathi = "नमस्कार " * 4000

        self.assertGreater(ma.choose_context_size(marathi), 4096)


class AnalyzeMeetingTests(unittest.TestCase):

    def sent_to_ollama(self, post: mock.Mock) -> dict:

        return post.call_args.kwargs["json"]

    def test_a_good_answer_is_marked_llm_and_thinking_is_off(self):

        with mock.patch.object(ma.requests, "post", return_value=fake_ollama_answer(GOOD_ANSWER)) as post:

            result = ma.analyze_meeting_with_llm("Vivek will complete the design next Monday.")

        self.assertEqual(result["analysisSource"], "LLM")
        self.assertEqual(result["actionItems"][0]["dueDate"], "next Monday")

        sent = self.sent_to_ollama(post)

        self.assertIs(sent["think"], False)
        self.assertEqual(sent["format"], "json")

    def test_a_short_meeting_does_not_ask_for_a_bigger_window(self):

        with mock.patch.object(ma.requests, "post", return_value=fake_ollama_answer(GOOD_ANSWER)) as post:
            ma.analyze_meeting_with_llm("Short meeting.")

        self.assertNotIn("num_ctx", self.sent_to_ollama(post)["options"])

    def test_a_long_meeting_asks_for_a_bigger_window(self):

        with mock.patch.object(ma.requests, "post", return_value=fake_ollama_answer(GOOD_ANSWER)) as post:
            ma.analyze_meeting_with_llm("word " * 4500)

        self.assertEqual(self.sent_to_ollama(post)["options"]["num_ctx"], 16384)

    def test_an_unreachable_ollama_uses_the_fallback_and_says_so(self):

        transcript = "Vivek will complete the frontend design by Wednesday. We decided to use React."

        with mock.patch.object(ma.requests, "post", side_effect=ma.requests.ConnectionError("refused")):
            with redirect_stdout(io.StringIO()):
                result = ma.analyze_meeting_with_llm(transcript)

        self.assertEqual(result["analysisSource"], "FALLBACK")
        self.assertTrue(result["summary"])
        self.assertEqual(result["actionItems"][0]["owner"], "Vivek")
        self.assertIn("We decided to use React.", result["keyDecisions"])

    def test_an_answer_that_is_not_valid_json_uses_the_fallback(self):

        broken = mock.Mock()

        broken.json.return_value = {"message": {"content": "this is not json"}}

        with mock.patch.object(ma.requests, "post", return_value=broken):
            with redirect_stdout(io.StringIO()):
                result = ma.analyze_meeting_with_llm("Vivek will finish the design.")

        self.assertEqual(result["analysisSource"], "FALLBACK")

    def test_an_answer_without_a_summary_uses_the_fallback(self):

        answer = {"keyDecisions": [], "actionItems": []}

        with mock.patch.object(ma.requests, "post", return_value=fake_ollama_answer(answer)):
            with redirect_stdout(io.StringIO()):
                result = ma.analyze_meeting_with_llm("Vivek will finish the design.")

        self.assertEqual(result["analysisSource"], "FALLBACK")


class EnvironmentSettingsTests(unittest.TestCase):

    def tearDown(self):

        importlib.reload(ma)

    def test_ollama_address_and_model_can_be_set_with_environment_variables(self):

        settings = {"OLLAMA_URL": "http://example.test:1/api/chat", "OLLAMA_MODEL": "other:1b"}

        with mock.patch.dict(os.environ, settings):
            importlib.reload(ma)

            self.assertEqual(ma.OLLAMA_URL, "http://example.test:1/api/chat")
            self.assertEqual(ma.OLLAMA_MODEL, "other:1b")

    def test_defaults_apply_when_nothing_is_set(self):

        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("OLLAMA_URL", None)
            os.environ.pop("OLLAMA_MODEL", None)

            importlib.reload(ma)

            self.assertEqual(ma.OLLAMA_URL, "http://127.0.0.1:11434/api/chat")
            self.assertEqual(ma.OLLAMA_MODEL, "qwen3:8b")


if __name__ == "__main__":
    unittest.main()
