#!/bin/bash
# The harness command of evaluation/draftwatch.eval.yaml: the reference harness in the vLLM
# environment, with the caches of env.sh and no network access to the Hugging Face Hub.
source "$(dirname "$0")/env.sh"
export HF_HUB_OFFLINE=1
export PATH="$GPU_VENV/bin:$PATH"  # the venv's tools, such as ninja
exec "$GPU_VENV/bin/python" "$REPO/python/measure_acceptance.py" "$@"
