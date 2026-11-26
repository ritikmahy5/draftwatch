"""Text normalization shared by deduplication and the probe/training-data disjointness check."""


def normalize(text):
    """Lowercases ``text`` and collapses every run of whitespace to one space, trimmed."""
    return " ".join(text.lower().split())
