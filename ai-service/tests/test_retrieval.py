"""Unit tests for ai-service/retrieval.py.

They need no Ollama: the embedding model is replaced by a stand-in that
simply counts words, which is enough to test the cutting, the ranking, the
caching and the error messages.

Run them from the ai-service folder:

    python -m unittest discover -s tests -v
"""

import re
import sys
import unittest
import zlib
from pathlib import Path
from unittest import mock

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import retrieval  # noqa: E402


IGNORED = {"search", "document", "query", "the", "a", "who", "what", "is", "of", "in", "it"}


def word_count_embedder(calls: list):

    def embed(texts):

        calls.append(list(texts))

        vectors = np.zeros((len(texts), 256), dtype=np.float32)

        for row, text in enumerate(texts):

            for word in re.findall(r"[a-z0-9]+", text.lower()):

                if word not in IGNORED:

                    vectors[row, zlib.crc32(word.encode()) % 256] += 1

        lengths = np.linalg.norm(vectors, axis=1, keepdims=True)

        lengths[lengths == 0] = 1

        return vectors / lengths

    return embed


def numbered_sentences(count: int) -> str:

    return " ".join(
        f"Sentence number {number} talks about topic{number} for a while."
        for number in range(count)
    )


class PassageTests(unittest.TestCase):

    def test_empty_text_has_no_passages(self):

        self.assertEqual(retrieval.make_passages(""), [])
        self.assertEqual(retrieval.make_passages("   \n "), [])

    def test_a_short_text_is_one_passage(self):

        text = "We start now. Vivek will test. Done."

        passages = retrieval.make_passages(text)

        self.assertEqual(len(passages), 1)
        self.assertEqual(passages[0].text, text)

    def test_the_offsets_point_at_the_original_text(self):

        text = "  " + numbered_sentences(40)

        for passage in retrieval.make_passages(text):

            self.assertEqual(passage.text, text[passage.start:passage.end])
            self.assertEqual(passage.text, passage.text.strip())

    def test_every_sentence_is_kept_and_the_order_is_preserved(self):

        text = numbered_sentences(60)

        passages = retrieval.make_passages(text)

        for number in range(60):

            self.assertTrue(
                any(f"topic{number} " in passage.text for passage in passages),
                f"sentence {number} is missing",
            )

        starts = [passage.start for passage in passages]

        self.assertEqual(starts, sorted(starts))
        self.assertEqual([p.index for p in passages], list(range(len(passages))))

    def test_passages_are_about_the_target_size(self):

        longest_sentence = 10

        for passage in retrieval.make_passages(numbered_sentences(80), target_words=100):

            self.assertLessEqual(len(passage.text.split()), 100 + longest_sentence)

    def test_neighbouring_passages_share_one_sentence(self):

        passages = retrieval.make_passages(numbered_sentences(60))

        self.assertGreater(len(passages), 2)

        for before, after in zip(passages, passages[1:]):

            last_sentence = re.split(r"(?<=[.!?])\s+", before.text)[-1]

            self.assertTrue(after.text.startswith(last_sentence))

    def test_one_huge_sentence_is_not_repeated_again_and_again(self):

        huge = "word " * 300 + "end."

        text = f"{huge} Short one. Another short one. A last one."

        passages = retrieval.make_passages(text)

        # the huge sentence is cut into pieces, so no passage repeats all of it
        self.assertEqual(sum(huge.strip() in passage.text for passage in passages), 0)
        self.assertLessEqual(max(len(passage.text.split()) for passage in passages), 200)

    def test_text_without_any_full_stop_is_cut_into_passages(self):

        text = " ".join(f"word{n}" for n in range(1000))

        passages = retrieval.make_passages(text)

        self.assertGreater(len(passages), 5)
        self.assertLessEqual(max(len(passage.text.split()) for passage in passages), 200)

    def test_a_hindi_full_stop_ends_a_sentence(self):

        spans = retrieval.sentence_spans("यह पहला है। यह दूसरा है।")

        self.assertEqual(len(spans), 2)


class EmbeddingTests(unittest.TestCase):

    def answer(self, vectors):

        response = mock.Mock(status_code=200)

        response.json.return_value = {"embeddings": vectors}

        return response

    def test_vectors_come_back_with_length_one(self):

        with mock.patch.object(retrieval.requests, "post", return_value=self.answer([[3.0, 4.0]])) as post:

            matrix = retrieval.embed_texts(["hello"])

        self.assertAlmostEqual(float(np.linalg.norm(matrix[0])), 1.0, places=5)

        sent = post.call_args.kwargs["json"]

        self.assertEqual(sent["model"], retrieval.EMBED_MODEL)
        self.assertEqual(sent["input"], ["hello"])

    def test_many_texts_are_sent_in_batches(self):

        def respond(url, json, timeout):

            return self.answer([[1.0, 0.0]] * len(json["input"]))

        with mock.patch.object(retrieval.requests, "post", side_effect=respond) as post:

            matrix = retrieval.embed_texts(["x"] * 130)

        self.assertEqual(matrix.shape, (130, 2))
        self.assertEqual(post.call_count, 3)

    def test_nothing_to_embed_sends_no_request(self):

        with mock.patch.object(retrieval.requests, "post") as post:

            self.assertEqual(retrieval.embed_texts([]).shape[0], 0)

        post.assert_not_called()

    def test_an_unreachable_ollama_is_explained(self):

        with mock.patch.object(retrieval.requests, "post", side_effect=retrieval.requests.ConnectionError("refused")):

            with self.assertRaises(retrieval.RetrievalError) as error:
                retrieval.embed_texts(["hello"])

        self.assertIn("not reachable", str(error.exception))

    def test_a_missing_model_tells_how_to_install_it(self):

        with mock.patch.object(retrieval.requests, "post", return_value=mock.Mock(status_code=404)):

            with self.assertRaises(retrieval.RetrievalError) as error:
                retrieval.embed_texts(["hello"])

        self.assertIn(f"ollama pull {retrieval.EMBED_MODEL}", str(error.exception))

    def test_an_unexpected_answer_is_explained(self):

        broken = mock.Mock(status_code=200)

        broken.json.return_value = {"something": "else"}

        with mock.patch.object(retrieval.requests, "post", return_value=broken):

            with self.assertRaises(retrieval.RetrievalError) as error:
                retrieval.embed_texts(["hello"])

        self.assertIn("unexpected", str(error.exception))


class PrefixTests(unittest.TestCase):

    def test_nomic_models_get_their_markers(self):

        self.assertEqual(
            retrieval.prefixes_for("nomic-embed-text"),
            ("search_document: ", "search_query: "),
        )

    def test_other_models_get_none(self):

        self.assertEqual(retrieval.prefixes_for("bge-m3"), ("", ""))


class SearchTests(unittest.TestCase):

    TRANSCRIPT = (
        numbered_sentences(12)
        + " Meera owns the Stripe integration and starts immediately. "
        + numbered_sentences(12).replace("topic", "other")
    )

    def setUp(self):

        retrieval._cache.clear()

        self.calls: list = []

        patcher = mock.patch.object(retrieval, "embed_texts", side_effect=word_count_embedder(self.calls))

        patcher.start()

        self.addCleanup(patcher.stop)

    def test_the_passage_with_the_matching_words_comes_first(self):

        hits = retrieval.search(self.TRANSCRIPT, "Who owns the Stripe integration?")

        self.assertIn("Stripe integration", hits[0].passage.text)

    def test_results_are_best_first_and_limited_to_top_k(self):

        hits = retrieval.search(self.TRANSCRIPT, "Stripe integration", top_k=2)

        self.assertEqual(len(hits), 2)
        self.assertGreaterEqual(hits[0].score, hits[1].score)

    def test_asking_for_more_than_exists_returns_everything(self):

        hits = retrieval.search("One short sentence only.", "sentence", top_k=10)

        self.assertEqual(len(hits), 1)

    def test_the_second_question_reuses_the_numbers_of_the_transcript(self):

        retrieval.search(self.TRANSCRIPT, "Stripe integration")

        calls_after_first = len(self.calls)

        retrieval.search(self.TRANSCRIPT, "Who is Meera?")

        self.assertEqual(calls_after_first, 2)
        self.assertEqual(len(self.calls), 3)

    def test_a_different_transcript_is_embedded_again(self):

        retrieval.search(self.TRANSCRIPT, "Stripe")

        retrieval.search(self.TRANSCRIPT + " One more sentence.", "Stripe")

        self.assertEqual(len(self.calls), 4)

    def test_the_cache_does_not_grow_without_limit(self):

        for number in range(retrieval.MAX_CACHED_TRANSCRIPTS + 3):

            retrieval.search(f"Transcript number {number} is here.", "number")

        self.assertLessEqual(len(retrieval._cache), retrieval.MAX_CACHED_TRANSCRIPTS)

    def test_an_empty_question_is_refused(self):

        with self.assertRaises(ValueError):
            retrieval.search(self.TRANSCRIPT, "   ")

    def test_an_empty_transcript_finds_nothing(self):

        self.assertEqual(retrieval.search("", "anything"), [])

    def test_nomic_markers_are_added_to_passages_and_to_the_question(self):

        retrieval.search("Meera owns the integration.", "Who owns it?")

        passage_batch, question_batch = self.calls

        self.assertTrue(passage_batch[0].startswith("search_document: "))
        self.assertTrue(question_batch[0].startswith("search_query: "))

    def test_other_models_get_no_markers(self):

        with mock.patch.object(retrieval, "EMBED_MODEL", "bge-m3"):

            retrieval.search("Meera owns the integration.", "Who owns it?")

        self.assertFalse(self.calls[0][0].startswith("search_document"))
        self.assertFalse(self.calls[1][0].startswith("search_query"))


if __name__ == "__main__":
    unittest.main()
