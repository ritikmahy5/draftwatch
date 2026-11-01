"""Selects the fine-tuning data (docs/EVALUATION.md, "Fine-tuning runs").

Run 1 (the trunk) and run 2 (the branch) draw disjoint UltraChat first turns, and no training
prompt may equal a probe prompt after normalization.
"""

import random
from dataclasses import dataclass

from draftwatch_eval.text import normalize

MAX_RESPONSE_TOKENS = 768
MAX_EXAMPLE_TOKENS = 1024


class TrainingDataError(Exception):
    """The training data cannot meet the design: an overlap with a probe, or too few examples."""


@dataclass(frozen=True)
class Turn:
    prompt_id: str
    prompt: str
    reply: str


def first_turns(rows):
    """The first user message and first assistant reply of each conversation.

    Returns (turns, skipped), where skipped counts conversations that do not start with a user
    message followed by an assistant message.
    """
    turns = []
    skipped = 0
    for row in rows:
        messages = row["messages"]
        if (len(messages) >= 2 and messages[0]["role"] == "user"
                and messages[1]["role"] == "assistant"):
            turns.append(Turn(row["prompt_id"], messages[0]["content"], messages[1]["content"]))
        else:
            skipped += 1
    return turns, skipped


def disjoint_split(turns, probe_prompts, trunk_candidates, branch_candidates, seed):
    """Trunk and branch candidates with no prompt id in common, drawn by a seeded shuffle of the
    turns sorted by prompt id.

    Raises TrainingDataError naming the prompt id if any turn's prompt equals a probe prompt
    after normalization, or with the counts if there are too few turns.
    """
    probes = {normalize(p) for p in probe_prompts}
    for t in turns:
        if normalize(t.prompt) in probes:
            raise TrainingDataError("training prompt %s is also a probe prompt" % t.prompt_id)
    needed = trunk_candidates + branch_candidates
    if len(turns) < needed:
        raise TrainingDataError(
            "%d turns available, %d needed (%d trunk + %d branch)"
            % (len(turns), needed, trunk_candidates, branch_candidates))
    ordered = sorted(turns, key=lambda t: t.prompt_id)
    random.Random(seed).shuffle(ordered)
    return ordered[:trunk_candidates], ordered[trunk_candidates:needed]


def keep(response_tokens, total_tokens, ended):
    """True if an example fits the length limits and its response ended on its own."""
    return (ended and response_tokens <= MAX_RESPONSE_TOKENS
            and total_tokens <= MAX_EXAMPLE_TOKENS)


def take(examples, needed, what):
    """The first ``needed`` examples; raises TrainingDataError with the counts if there are
    fewer."""
    out = []
    for example in examples:
        out.append(example)
        if len(out) == needed:
            return out
    raise TrainingDataError("only %d %s, %d needed" % (len(out), what, needed))
