#!/usr/bin/env python3
"""Builds the four probe files from pinned dataset revisions (docs/EVALUATION.md, "Probes").

Usage: build_probes.py --out <dir>

Writes chat.jsonl, chat-live.jsonl, code.jsonl, code-live.jsonl, and probes.sha256.json. A rebuild
whose bytes differ from the recorded hashes fails and writes nothing. Needs the datasets library
(checked against datasets 5.1.0, src/datasets/load.py: load_dataset(path, split=, revision=)).
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from draftwatch_eval.manifest import write_files  # noqa: E402
from draftwatch_eval.probes import code_prompts, jsonl, select_chat, split  # noqa: E402

OASST2 = ("OpenAssistant/oasst2", "179dd21fc55192153d94adb0e0ce8f69e222bf75",
          ("train", "validation"))
HUMANEVAL = ("openai/openai_humaneval", "7dce6050a7d6d172f3cc5c32aa97f52fa1a2e544", ("test",))
MBPP = ("google-research-datasets/mbpp", "4bb6404fdc6cacfda99d4ac4205087b89d32030c",
        ("train", "test", "validation", "prompt"))
POOL, LIVE, SEED = 1000, 100, 0
MANIFEST = "probes.sha256.json"


def rows(source):
    """Every row of every listed split of a pinned dataset revision."""
    from datasets import load_dataset

    path, revision, splits = source
    out = []
    for name in splits:
        out.extend(load_dataset(path, split=name, revision=revision))
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, type=Path, help="directory for the probe files")
    args = parser.parse_args(argv)

    chat, chat_live = select_chat(rows(OASST2), POOL, LIVE, SEED)
    code = [text for _, text in code_prompts(rows(HUMANEVAL), rows(MBPP))]
    code_pool, code_live = split(code, POOL, LIVE, SEED)
    hashes = write_files(
        args.out,
        {
            "chat.jsonl": jsonl(chat),
            "chat-live.jsonl": jsonl(chat_live),
            "code.jsonl": jsonl(code_pool),
            "code-live.jsonl": jsonl(code_live),
        },
        MANIFEST,
    )
    for name in sorted(hashes):
        print("%s  %s" % (hashes[name], name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
