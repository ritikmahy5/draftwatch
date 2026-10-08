#!/bin/bash
# draftwatch end to end on this machine, with the fake harness: no GPU, no cluster.
#
# Usage: scripts/demo.sh [<empty or new directory>]   (default: a new temporary directory)
#
# Builds the launcher, then in the directory: writes a config, creates three checkpoints, and
# runs `watch --once` until no job is active (local jobs run in the background between passes).
# Before the third checkpoint the fake harness is pointed at lower synthetic counts, so the
# detectors report a regression. Every number comes from the synthetic fixtures in
# src/test/resources/fixtures/, not from a measurement.
set -euo pipefail
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
D="${1:-$(mktemp -d "${TMPDIR:-/tmp}/draftwatch-demo.XXXXXX")}"
mkdir -p "$D"
if [ -n "$(ls -A "$D")" ]; then
  echo "demo: $D is not empty" >&2
  exit 2
fi
"$R/gradlew" -q -p "$R" installDist
DW="$R/build/install/draftwatch/bin/draftwatch"
cd "$D"
run() { echo; echo "\$ draftwatch $*"; "$DW" "$@"; }
settle() {  # watch --once until no job is active
  for _ in $(seq 1 30); do
    run watch --once
    if "$DW" status | grep -q '^active jobs: 0$'; then
      return 0
    fi
    sleep 1
  done
  echo "demo: jobs are still active after 30 passes; see 'draftwatch status' in $D" >&2
  exit 1
}

mkdir -p draft runs
head -c 65536 /dev/urandom > draft/model.safetensors
printf '%s\n' \
  '{"messages": [{"role": "user", "content": "Explain recursion."}]}' \
  '{"messages": [{"role": "user", "content": "Write a haiku about rain."}]}' \
  '{"messages": [{"role": "user", "content": "List three prime numbers."}]}' > prompts.jsonl
checkpoint() {  # <step>: random weights, the step, and the completion marker, written last
  mkdir -p "runs/checkpoint-$1"
  head -c 65536 /dev/urandom > "runs/checkpoint-$1/model.safetensors"
  echo "{\"global_step\": $1}" > "runs/checkpoint-$1/trainer_state.json"
  touch "runs/checkpoint-$1/DONE"
}
config() {  # <fixture>: the synthetic counts the fake harness reports
  cat > draftwatch.yaml <<YAML
state_dir: .draftwatch
executor: { type: local, max_retries: 0 }
harness:
  command: ["env", "DRAFTWATCH_FAKE_FIXTURE=$R/src/test/resources/fixtures/$1",
            "python3", "$R/scripts/fake_harness.py"]
probes:
  - id: chat
    draft: { id: my-draft, path: draft, structure: chain }
    prompts: { path: prompts.jsonl }
    decoding: { temperature: 0, max_new_tokens: 64, num_speculative_tokens: 3, dtype: bfloat16 }
    seeds: [0]
targets:
  - name: my-run
    checkpoint_dirs: [runs]
    checkpoint_type: full
    completion: { marker: DONE }
    probes: [chat]
    detectors:
      - paired_bootstrap: { metric: alpha }
      - absolute_drop: { metric: alpha, max_drop: 0.05 }
YAML
}

config synthetic_three_prompts.json
run validate
checkpoint 100
settle
checkpoint 200
settle
echo; echo "# the fake harness now reports lower synthetic counts"
config synthetic_three_prompts_lower.json
checkpoint 300
settle
run history my-run --probe chat
run report --out report.html
echo
echo "demo directory: $D (config, checkpoints, .draftwatch state, report.html)"
