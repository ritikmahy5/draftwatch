"""Arguments, ``--decoding-json``, and the prompt file (contract "Invocation", "Prompt file")."""

import argparse
import hashlib
import json

from .errors import EXIT_BAD_ARGUMENTS, HarnessError

ESTIMATORS = ("token_weighted", "simple_mean")
DECODING_KEYS = ("temperature", "max_new_tokens", "num_speculative_tokens", "dtype")


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise HarnessError(EXIT_BAD_ARGUMENTS, message)


def parse_args(argv):
    parser = _Parser(prog="measure_acceptance", allow_abbrev=False)
    parser.add_argument("--target-checkpoint", required=True)
    parser.add_argument("--base-model")
    parser.add_argument("--draft-id", required=True)
    parser.add_argument("--draft-path", required=True)
    parser.add_argument("--prompts", required=True)
    parser.add_argument("--decoding-json", required=True)
    parser.add_argument("--estimator", required=True, choices=ESTIMATORS)
    parser.add_argument("--seeds", required=True)
    parser.add_argument("--out", required=True)
    return parser.parse_args(argv)


def parse_seeds(text):
    try:
        seeds = [int(part) for part in text.split(",")]
    except ValueError:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "--seeds must be comma-separated integers")
    if len(set(seeds)) != len(seeds):
        raise HarnessError(EXIT_BAD_ARGUMENTS, "--seeds has a repeated seed")
    return seeds


def parse_decoding(text):
    """The decoding object: exactly the four keys of the report schema."""
    try:
        decoding = json.loads(text)
    except ValueError as e:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "--decoding-json is not JSON: %s" % e)
    if not isinstance(decoding, dict) or sorted(decoding) != sorted(DECODING_KEYS):
        raise HarnessError(
            EXIT_BAD_ARGUMENTS, "--decoding-json must have exactly the keys " + ", ".join(DECODING_KEYS))
    t = decoding["temperature"]
    if isinstance(t, bool) or not isinstance(t, (int, float)) or t < 0:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "decoding.temperature must be a number >= 0")
    for key in ("max_new_tokens", "num_speculative_tokens"):
        v = decoding[key]
        if isinstance(v, bool) or not isinstance(v, int) or v < 1:
            raise HarnessError(EXIT_BAD_ARGUMENTS, "decoding.%s must be an integer >= 1" % key)
    if not isinstance(decoding["dtype"], str) or not decoding["dtype"]:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "decoding.dtype must be a non-empty string")
    return decoding


class Prompt:
    """One prompt: exactly one of ``prompt`` (text) or ``messages`` (chat)."""

    def __init__(self, index, text=None, messages=None):
        self.index = index
        self.text = text
        self.messages = messages


def read_prompts(path):
    """``(sha256, prompts)`` per the contract's "Prompt file" rules."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "cannot read --prompts %s: %s" % (path, e))
    if data.startswith(b"\xef\xbb\xbf"):
        raise HarnessError(EXIT_BAD_ARGUMENTS, "prompt file starts with a byte-order mark")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "prompt file is not UTF-8")
    prompts = []
    for number, line in enumerate(text.split("\n"), start=1):
        if line.strip(" \t\r") == "":
            continue
        try:
            value = json.loads(line)
        except ValueError:
            raise HarnessError(EXIT_BAD_ARGUMENTS, "prompt line %d is not JSON" % number)
        if not isinstance(value, dict):
            raise HarnessError(EXIT_BAD_ARGUMENTS, "prompt line %d is not a JSON object" % number)
        keys = set(value)
        if keys == {"prompt"} and isinstance(value["prompt"], str):
            prompts.append(Prompt(len(prompts), text=value["prompt"]))
        elif keys == {"messages"} and isinstance(value["messages"], list) and value["messages"]:
            prompts.append(Prompt(len(prompts), messages=value["messages"]))
        else:
            raise HarnessError(
                EXIT_BAD_ARGUMENTS,
                "prompt line %d must have exactly one of 'prompt' (a string) or 'messages'"
                " (a non-empty list)" % number)
    if not prompts:
        raise HarnessError(EXIT_BAD_ARGUMENTS, "prompt file has no prompts")
    return hashlib.sha256(data).hexdigest(), prompts
