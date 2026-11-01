#!/bin/bash
#SBATCH --job-name=eval-pilot
#SBATCH --partition=gpu-short
#SBATCH --gres=gpu:h200:1
#SBATCH --time=01:00:00
#SBATCH --cpus-per-task=8
#SBATCH --mem=64G
# One pilot measurement (P2-P4) with the reference harness, outside draftwatch, using the
# evaluation probes' decoding. Pick the node with sbatch's own --nodelist (P3).
# Usage: sbatch [--nodelist=<node>] harness_direct.sbatch <target> <draft id> <draft dir> \
#            <prompts.jsonl> <report.json>
set -euo pipefail
echo "== job $SLURM_JOB_ID on $(hostname), $(date -u +%FT%TZ)"
nvidia-smi --query-gpu=name,uuid,driver_version --format=csv
# Slurm runs a copy of this script, so harness.sh is found through the repository, not $0.
"${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}/evaluation/cluster/harness.sh" \
  --target-checkpoint "$1" --draft-id "$2" --draft-path "$3" --prompts "$4" \
  --decoding-json '{"temperature": 0, "max_new_tokens": 128, "num_speculative_tokens": 4, "dtype": "bfloat16"}' \
  --estimator token_weighted --seeds 0 --out "$5"
echo "== exit $? at $(date -u +%FT%TZ)"
