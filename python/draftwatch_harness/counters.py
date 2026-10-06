"""Counter snapshots and per-prompt deltas (contract "Reference backend"; D80, D82, D83).

The four engine counters are cumulative and engine-wide. A prompt's counts are the difference
between snapshots taken just before and just after its own ``generate()`` call.
"""

from .errors import EXIT_BACKEND_COUNTERS, HarnessError

DRAFTS = "vllm:spec_decode_num_drafts"
DRAFT_TOKENS = "vllm:spec_decode_num_draft_tokens"
ACCEPTED = "vllm:spec_decode_num_accepted_tokens"
ACCEPTED_PER_POS = "vllm:spec_decode_num_accepted_tokens_per_pos"
NAMES = (DRAFTS, DRAFT_TOKENS, ACCEPTED, ACCEPTED_PER_POS)


class Snapshot:
    """The four counters at one moment, summed over label sets (engines), as vLLM's example does."""

    def __init__(self, drafts, draft_tokens, accepted, per_pos):
        self.drafts = drafts
        self.draft_tokens = draft_tokens
        self.accepted = accepted
        self.per_pos = list(per_pos)

    @classmethod
    def from_metrics(cls, metrics, k):
        """From ``LLM.get_metrics()``: objects with ``name`` and ``value`` (counters) or
        ``values`` (the per-position vector).

        :raises HarnessError: exit 5 naming a missing counter, or a vector of the wrong length
        """
        totals = {DRAFTS: None, DRAFT_TOKENS: None, ACCEPTED: None}
        per_pos = None
        for m in metrics:
            if m.name in totals:
                totals[m.name] = (totals[m.name] or 0) + int(m.value)
            elif m.name == ACCEPTED_PER_POS:
                values = [int(v) for v in m.values]
                if len(values) != k:
                    raise HarnessError(
                        EXIT_BACKEND_COUNTERS,
                        "%s has %d positions, num_speculative_tokens is %d"
                        % (ACCEPTED_PER_POS, len(values), k))
                per_pos = values if per_pos is None else [a + b for a, b in zip(per_pos, values)]
        for name, value in totals.items():
            if value is None:
                raise HarnessError(EXIT_BACKEND_COUNTERS, "vLLM reports no metric " + name)
        if per_pos is None:
            raise HarnessError(EXIT_BACKEND_COUNTERS, "vLLM reports no metric " + ACCEPTED_PER_POS)
        return cls(totals[DRAFTS], totals[DRAFT_TOKENS], totals[ACCEPTED], per_pos)

    def to_json(self):
        return {"drafts": self.drafts, "draft_tokens": self.draft_tokens,
                "accepted": self.accepted, "accepted_per_pos": self.per_pos}

    @classmethod
    def from_json(cls, node):
        return cls(node["drafts"], node["draft_tokens"], node["accepted"], node["accepted_per_pos"])


class PromptCounts:
    """One prompt's contract counts: steps, proposed, accepted, accepted at each position."""

    def __init__(self, steps, proposed, accepted, per_pos):
        self.steps = steps
        self.proposed = proposed
        self.accepted = accepted
        self.per_pos = list(per_pos)


def delta(before, after, k, what):
    """The counts between two snapshots, checked (D82).

    :raises HarnessError: exit 5 if a counter decreased, the per-position counts are not
        non-increasing (acceptance is not prefix-based), or the counts contradict each other
    """
    steps = after.drafts - before.drafts
    proposed = after.draft_tokens - before.draft_tokens
    accepted = after.accepted - before.accepted
    per_pos = [a - b for a, b in zip(after.per_pos, before.per_pos)]
    if min([steps, proposed, accepted] + per_pos) < 0:
        raise HarnessError(EXIT_BACKEND_COUNTERS, "a counter decreased during " + what)
    for j in range(1, k):
        if per_pos[j] > per_pos[j - 1]:
            raise HarnessError(
                EXIT_BACKEND_COUNTERS,
                "acceptance is not prefix-based during %s: %d accepted at position %d but %d at"
                " position %d, so eligible counts cannot be derived" % (what, per_pos[j], j + 1,
                                                                         per_pos[j - 1], j))
    if k and per_pos[0] > steps:
        raise HarnessError(EXIT_BACKEND_COUNTERS,
                           "more accepted at position 1 than steps during " + what)
    if sum(per_pos) != accepted:
        raise HarnessError(
            EXIT_BACKEND_COUNTERS,
            "accepted per position sums to %d, but %d tokens were accepted during %s"
            % (sum(per_pos), accepted, what))
    if not accepted <= proposed <= steps * k:
        raise HarnessError(
            EXIT_BACKEND_COUNTERS,
            "counts out of order during %s: accepted %d, proposed %d, steps %d, k %d"
            % (what, accepted, proposed, steps, k))
    return PromptCounts(steps, proposed, accepted, per_pos)


def check_warm_up(before, after):
    """All four counters exist (checked when they were read) and drafting happened (D83)."""
    if after.drafts <= before.drafts or after.draft_tokens <= before.draft_tokens:
        raise HarnessError(
            EXIT_BACKEND_COUNTERS,
            "the warm-up prompt produced no drafts (%s and %s did not advance); with"
            " max_new_tokens 1 nothing is drafted" % (DRAFTS, DRAFT_TOKENS))
