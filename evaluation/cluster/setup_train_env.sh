#!/bin/bash
#SBATCH --job-name=eval-setup
#SBATCH --partition=short
#SBATCH --time=01:00:00
#SBATCH --cpus-per-task=4
#SBATCH --mem=16G
# Creates the training environment. Run as a job: sbatch evaluation/cluster/setup_train_env.sh
set -euo pipefail
# Slurm runs a copy of this script, so env.sh is found through the repository, not $0.
source "${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}/evaluation/cluster/env.sh"
if [ ! -x "$TRAIN_VENV/bin/python" ]; then
  /shared/EL9/explorer/anaconda3/2024.06/bin/python3 -m venv "$TRAIN_VENV"
fi
"$TRAIN_VENV/bin/python" -m pip install --quiet --upgrade pip
"$TRAIN_VENV/bin/python" -m pip install --quiet -r "$REPO/evaluation/cluster/requirements-train.txt" \
  --extra-index-url https://download.pytorch.org/whl/cu129
"$TRAIN_VENV/bin/python" -c "import torch, transformers, datasets, accelerate; print('torch', torch.__version__, 'cuda', torch.version.cuda, 'transformers', transformers.__version__, 'datasets', datasets.__version__, 'accelerate', accelerate.__version__)"
