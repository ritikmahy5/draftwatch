"""Selects and formats the probe prompts (docs/EVALUATION.md, "Probes").

Every function takes plain rows (dicts), so the selection is tested without the datasets
library; ``build_probes.py`` loads the pinned dataset revisions and passes their rows in.
"""

import json
import random

from draftwatch_eval.text import normalize

MAX_CHAT_CHARACTERS = 2000
CHAT_FIELDS = ("message_id", "parent_id", "text", "role", "lang", "synthetic", "deleted",
               "review_result")

HUMANEVAL_INSTRUCTION = (
    "Complete the following Python function. Reply with the complete function in one Python"
    " code block.\n\n```python\n")
MBPP_TEST_INTRO = "\n\nYour code should pass this test:\n"
MBPP_INSTRUCTION = "\n\nReply with the function in one Python code block."


class ProbeDataError(Exception):
    """A source row is malformed, or too few prompts pass the filters."""


def _field(row, name, row_id):
    if name not in row:
        raise ProbeDataError("row %s has no field %r" % (row_id, name))
    return row[name]


def _eligible_chat(row):
    row_id = row.get("message_id", "<no message_id>")
    values = {name: _field(row, name, row_id) for name in CHAT_FIELDS}
    return (values["role"] == "prompter"
            and values["parent_id"] is None
            and values["lang"] == "en"
            and values["synthetic"] is False
            and values["deleted"] is False
            and values["review_result"] is True
            and len(values["text"]) <= MAX_CHAT_CHARACTERS)


def select_chat(rows, pool_size=1000, live_size=100, seed=0):
    """The chat pool and the chat-live probe: disjoint, deduplicated first-turn prompts.

    Eligible rows are sorted by message id, so the result does not depend on the input order;
    duplicates after normalization keep their first occurrence in that order.
    """
    eligible = sorted((r for r in rows if _eligible_chat(r)), key=lambda r: r["message_id"])
    seen = set()
    texts = []
    for r in eligible:
        key = normalize(r["text"])
        if key not in seen:
            seen.add(key)
            texts.append(r["text"])
    return split(texts, pool_size, live_size, seed)


def code_prompts(humaneval, mbpp):
    """Every HumanEval and MBPP problem as (source id, prompt text), in source order."""
    out = []
    for r in humaneval:
        task = _field(r, "task_id", r.get("task_id", "<no task_id>"))
        out.append(("humaneval/%s" % task,
                    HUMANEVAL_INSTRUCTION + _field(r, "prompt", task) + "```"))
    for r in mbpp:
        task = _field(r, "task_id", r.get("task_id", "<no task_id>"))
        tests = _field(r, "test_list", task)
        out.append(("mbpp/%s" % task,
                    _field(r, "text", task) + MBPP_TEST_INTRO + tests[0] + MBPP_INSTRUCTION))
    return out


def split(items, pool_size, live_size, seed):
    """A seeded shuffle of a copy of ``items``: the first ``pool_size``, then the next
    ``live_size``."""
    needed = pool_size + live_size
    if len(items) < needed:
        raise ProbeDataError(
            "%d prompts pass the filters, %d are needed (%d pool + %d live)"
            % (len(items), needed, pool_size, live_size))
    shuffled = list(items)
    random.Random(seed).shuffle(shuffled)
    return shuffled[:pool_size], shuffled[pool_size:needed]


def jsonl(prompts):
    """The prompt file: one ``messages`` object per line, UTF-8, LF line endings, no BOM."""
    lines = [json.dumps({"messages": [{"role": "user", "content": p}]}, ensure_ascii=False)
             for p in prompts]
    return ("\n".join(lines) + "\n").encode("utf-8")
