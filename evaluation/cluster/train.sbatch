#!/bin/bash
#SBATCH --job-name=eval-train
#SBATCH --partition=gpu
#SBATCH --gres=gpu:h200:1
#SBATCH --time=08:00:00
#SBATCH --cpus-per-task=8
#SBATCH --mem=128G
# One training run (evaluation/train.py); every argument is passed through.
# Usage: sbatch train.sbatch --run trunk --model <target> --data <trunk.jsonl> --out <dir>
set -euo pipefail
# Slurm runs a copy of this script, so env.sh is found through the repository, not $0.
source "${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}/evaluation/cluster/env.sh"
export HF_HUB_OFFLINE=1
echo "== job $SLURM_JOB_ID on $(hostname), $(date -u +%FT%TZ)"
nvidia-smi --query-gpu=name,driver_version --format=csv
"$TRAIN_VENV/bin/python" "$REPO/evaluation/train.py" "$@"
echo "== done $(date -u +%FT%TZ)"
