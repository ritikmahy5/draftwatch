#!/bin/bash
#SBATCH --job-name=eval-cpu
#SBATCH --partition=short
#SBATCH --time=04:00:00
#SBATCH --cpus-per-task=4
#SBATCH --mem=32G
# Runs one CPU step of the evaluation in the training environment, with network access to the
# Hugging Face Hub (downloads, build_probes.py, build_training_data.py select).
# Usage: sbatch cpu.sbatch python <script> <args...>
set -euo pipefail
# Slurm runs a copy of this script, so env.sh is found through the repository, not $0.
source "${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}/evaluation/cluster/env.sh"
export PATH="$TRAIN_VENV/bin:$PATH"
echo "== job $SLURM_JOB_ID on $(hostname), $(date -u +%FT%TZ): $*"
"$@"
echo "== done $(date -u +%FT%TZ)"
