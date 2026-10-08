"""A backend that replays recorded counter snapshots instead of running vLLM.

The fixture is what the harness writes with DRAFTWATCH_RECORD_COUNTERS: snapshots in the order
they were taken. ``generate`` must come between each "before" and "after" snapshot, so a replay
fails if the harness ever reads the counters in a different order than the recording.
"""

import json

import _paths  # noqa: F401
from draftwatch_harness.counters import Snapshot


class ReplayBackend:
    def __init__(self, fixture_path, backend="replay", hardware=None, adapter_handling="none"):
        with open(fixture_path, encoding="utf-8") as f:
            self.fixture = json.load(f)
        self.snapshots = self.fixture["snapshots"]
        self.next = 0
        self.calls = []
        self.backend = backend
        self.adapter_handling = adapter_handling
        self._hardware = hardware or {"gpu": "none", "count": 0}
        self.closed = False

    def snapshot(self):
        if self.next >= len(self.snapshots):
            raise AssertionError("the harness read more snapshots than were recorded")
        s = self.snapshots[self.next]
        self.next += 1
        return Snapshot.from_json(s["counters"])

    def generate(self, prompt, seed):
        label = self.snapshots[self.next]["label"] if self.next < len(self.snapshots) else None
        if label is None or not label.startswith(("after", "warm-up after")):
            raise AssertionError("generate() called before snapshot %r" % label)
        self.calls.append((prompt.index, seed))

    def hardware(self):
        return self._hardware

    def close(self):
        self.closed = True
