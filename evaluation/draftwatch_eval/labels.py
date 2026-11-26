"""Loss masking, data-order, and divergence checks for the training script (docs/EVALUATION.md)."""

IGNORE = -100  # the label value PyTorch's cross-entropy loss skips


def mask_prompt(prompt_ids, full_ids):
    """Labels for one example: IGNORE over the prompt, the token ids over the response.

    Raises ValueError unless ``prompt_ids`` is a proper prefix of ``full_ids``: otherwise the
    chat template rendered the prompt differently with a response attached, and masking by
    length would put loss on prompt tokens.
    """
    n = len(prompt_ids)
    if full_ids[:n] != list(prompt_ids):
        raise ValueError("the prompt's token ids are not a prefix of the example's")
    if len(full_ids) == n:
        raise ValueError("the example has no response tokens")
    return [IGNORE] * n + list(full_ids[n:])


def check_first_batch(batch_ids, expected_first):
    """Raises ValueError unless the first training batch starts with ``expected_first``."""
    if not batch_ids or batch_ids[0] != expected_first:
        raise ValueError("the first batch starts with %s, expected %s"
                         % (batch_ids[0] if batch_ids else "nothing", expected_first))



def check_finite_weights(step, nonfinite_names):
    """Raises ValueError unless ``nonfinite_names`` (parameters with a NaN or infinite value after
    ``step``) is empty. One non-finite gradient makes every weight non-finite after clipping, so
    training stops at the first such step instead of saving checkpoints that cannot be measured."""
    if nonfinite_names:
        raise ValueError("training diverged at step %d: %d parameters are not finite, first %s"
                         % (step, len(nonfinite_names), nonfinite_names[0]))


def check_unique_ids(ids):
    """Raises ValueError naming the first example id that appears twice."""
    seen = set()
    for example_id in ids:
        if example_id in seen:
            raise ValueError("example id %s appears more than once in the data" % example_id)
        seen.add(example_id)
