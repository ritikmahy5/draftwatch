#!/bin/bash
#SBATCH --job-name=eval-generate
#SBATCH --partition=gpu-short
#SBATCH --gres=gpu:h200:1
#SBATCH --time=02:00:00
#SBATCH --cpus-per-task=8
#SBATCH --mem=64G
# Greedy responses for one family's data with vLLM (build_training_data.py generate).
# Usage: sbatch generate.sbatch <generating model> <data dir> trunk|branch [<tokenizer model>]
set -euo pipefail
# Slurm runs a copy of this script, so env.sh is found through the repository, not $0.
source "${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}/evaluation/cluster/env.sh"
export HF_HUB_OFFLINE=1 PATH="$GPU_VENV/bin:$PATH"
echo "== job $SLURM_JOB_ID on $(hostname), $(date -u +%FT%TZ)"
nvidia-smi --query-gpu=name,driver_version --format=csv
args=(generate --model "$1" --data "$2" --prompts "$3")
if [ $# -ge 4 ]; then args+=(--tokenizer "$4"); fi
"$GPU_VENV/bin/python" "$REPO/evaluation/build_training_data.py" "${args[@]}"
echo "== done $(date -u +%FT%TZ)"
