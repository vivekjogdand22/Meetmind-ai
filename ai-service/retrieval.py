import hashlib
import os
import re
import threading
from collections import OrderedDict
from dataclasses import dataclass

import numpy as np
import requests


# ============================================================
# FINDING THE PASSAGES OF A TRANSCRIPT THAT FIT A QUESTION
#
# 1. The transcript is cut into passages of about 100 words.
# 2. Every passage is turned into a list of numbers (an "embedding")
#    that describes its meaning, using a small model run by Ollama.
# 3. The question is turned into numbers the same way. The passages
#    whose numbers are closest to the question are the best matches.
#
# Measured on the test meetings: the right passage was ranked first for
# 15 of 15 answerable questions with these settings.
# ============================================================

OLLAMA_EMBED_URL = os.getenv(
    "OLLAMA_EMBED_URL",
    "http://127.0.0.1:11434/api/embed",
)

EMBED_MODEL = os.getenv("OLLAMA_EMBED_MODEL", "nomic-embed-text")

PASSAGE_WORDS = 100

EMBED_BATCH_SIZE = 64

EMBED_TIMEOUT_SECONDS = 120

MAX_CACHED_TRANSCRIPTS = 8


class RetrievalError(Exception):
    """The embedding model could not be used. The message says why."""


@dataclass(frozen=True)
class Passage:
    index: int
    text: str
    start: int
    end: int


@dataclass(frozen=True)
class Hit:
    passage: Passage
    score: float


# ============================================================
# CUTTING A TRANSCRIPT INTO PASSAGES
# ============================================================

def sentence_spans(text: str) -> list[tuple[int, int]]:

    spans: list[tuple[int, int]] = []

    start = 0

    for gap in re.finditer(r"(?<=[.!?\u0964])\s+", text):

        spans.append((start, gap.start()))

        start = gap.end()

    spans.append((start, len(text)))


    trimmed = []

    for begin, end in spans:

        piece = text[begin:end]

        if piece.strip():

            begin += len(piece) - len(piece.lstrip())

            trimmed.extend(split_long(text, begin, end))

    return trimmed


MAX_SENTENCE_WORDS = 80

LONG_SENTENCE_PIECE_WORDS = 40


def split_long(text: str, begin: int, end: int) -> list[tuple[int, int]]:

    # A "sentence" with no full stop (unpunctuated speech) is cut into pieces,
    # otherwise the whole meeting would become one passage.
    words = list(re.finditer(r"\S+", text[begin:end]))

    if len(words) <= MAX_SENTENCE_WORDS:

        return [(begin, end)]

    pieces = []

    for first in range(0, len(words), LONG_SENTENCE_PIECE_WORDS):

        chunk = words[first:first + LONG_SENTENCE_PIECE_WORDS]

        pieces.append((begin + chunk[0].start(), begin + chunk[-1].end()))

    return pieces


def make_passages(
    text: str,
    target_words: int = PASSAGE_WORDS,
) -> list[Passage]:

    # Passages end at sentence boundaries. Each one starts with the last
    # sentence of the one before, so an answer that sits on a boundary is
    # still found whole.

    passages: list[Passage] = []

    current: list[tuple[int, int]] = []

    word_count = 0


    def close_passage() -> None:

        start, end = current[0][0], current[-1][1]

        passages.append(
            Passage(len(passages), text[start:end], start, end)
        )


    for begin, end in sentence_spans(text):

        words = len(text[begin:end].split())

        if current and word_count + words > target_words:

            close_passage()

            current = current[-1:] if len(current) > 1 else []

            word_count = (
                len(text[current[0][0]:current[0][1]].split())
                if current
                else 0
            )

        current.append((begin, end))

        word_count += words


    if current:

        close_passage()

    return passages


# ============================================================
# EMBEDDINGS
# ============================================================

def prefixes_for(model: str) -> tuple[str, str]:

    # nomic-embed-text was trained with these markers in front of the text
    # and finds better matches when they are present.

    if model.startswith("nomic-embed"):

        return "search_document: ", "search_query: "

    return "", ""


def embed_texts(texts: list[str]) -> np.ndarray:

    if not texts:

        return np.zeros((0, 0), dtype=np.float32)


    vectors: list[list[float]] = []

    for first in range(0, len(texts), EMBED_BATCH_SIZE):

        batch = texts[first:first + EMBED_BATCH_SIZE]

        try:

            response = requests.post(
                OLLAMA_EMBED_URL,
                json={"model": EMBED_MODEL, "input": batch},
                timeout=EMBED_TIMEOUT_SECONDS,
            )

        except requests.RequestException as exception:

            raise RetrievalError(
                "Ollama is not reachable, so the transcript cannot be "
                "searched. Start Ollama and try again."
            ) from exception


        if response.status_code == 404:

            raise RetrievalError(
                f"The embedding model {EMBED_MODEL} is not installed. "
                f"Run: ollama pull {EMBED_MODEL}"
            )


        try:

            response.raise_for_status()

            vectors.extend(response.json()["embeddings"])

        except Exception as exception:

            raise RetrievalError(
                "Ollama gave an unexpected answer while embedding the "
                "transcript."
            ) from exception


    matrix = np.array(vectors, dtype=np.float32)

    lengths = np.linalg.norm(matrix, axis=1, keepdims=True)

    lengths[lengths == 0] = 1

    return matrix / lengths


# ============================================================
# SEARCHING
#
# The numbers for a transcript are kept in memory, so the second question
# about the same meeting only has to embed the question itself.
# ============================================================

@dataclass(frozen=True)
class _Index:
    passages: list[Passage]
    matrix: np.ndarray


_cache: "OrderedDict[str, _Index]" = OrderedDict()

_cache_lock = threading.Lock()


def _index_for(transcript: str) -> _Index:

    key = hashlib.sha256(transcript.encode("utf-8")).hexdigest()

    with _cache_lock:

        cached = _cache.get(key)

        if cached is not None:

            _cache.move_to_end(key)

            return cached


    document_prefix, _ = prefixes_for(EMBED_MODEL)

    passages = make_passages(transcript)

    index = _Index(
        passages,
        embed_texts([document_prefix + p.text for p in passages]),
    )


    with _cache_lock:

        _cache[key] = index

        while len(_cache) > MAX_CACHED_TRANSCRIPTS:

            _cache.popitem(last=False)

    return index


def search(
    transcript: str,
    question: str,
    top_k: int = 4,
) -> list[Hit]:

    question = question.strip()

    if not question:

        raise ValueError("The question is empty")


    index = _index_for(transcript)

    if not index.passages:

        return []


    _, query_prefix = prefixes_for(EMBED_MODEL)

    query = embed_texts([query_prefix + question])[0]

    scores = index.matrix @ query

    best_first = np.argsort(-scores)[:top_k]

    return [
        Hit(index.passages[i], float(scores[i]))
        for i in best_first
    ]
