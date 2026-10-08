"""The reference measurement harness for draftwatch (MEASUREMENT_CONTRACT.md, "Reference backend").

Pure parts (arguments, prompts, counter deltas, the report, its validation) import only the
standard library, so they are tested without vLLM or a GPU. Only
``vllm_backend`` imports vLLM, and only when it runs.
"""

HARNESS_VERSION = "0.1.0"
SCHEMA_VERSION = 1
