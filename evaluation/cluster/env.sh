# Sourced by every evaluation job: where things live on the cluster, and caches kept in /scratch
# (home directories are small). Override DRAFTWATCH_EVAL_ROOT or DRAFTWATCH_REPO if needed.
export EVAL="${DRAFTWATCH_EVAL_ROOT:-/scratch/$USER/draftwatch-eval}"
export REPO="${DRAFTWATCH_REPO:-/scratch/$USER/draftwatch}"
export GPU_VENV="${DRAFTWATCH_GPU_VENV:-/scratch/$USER/draftwatch-gpu/venv-cu129}"  # vLLM 0.31.0
export TRAIN_VENV="$EVAL/venv-train"
export PATH="/usr/bin:/bin"
export HOME="$EVAL/home" HF_HOME="$EVAL/hf" TMPDIR="$EVAL/tmp" XDG_CACHE_HOME="$EVAL/cache"
export PIP_CACHE_DIR="$EVAL/pip-cache" VLLM_CACHE_ROOT="$EVAL/cache/vllm"
export TRITON_CACHE_DIR="$EVAL/cache/triton" TORCHINDUCTOR_CACHE_DIR="$EVAL/cache/inductor"
mkdir -p "$HOME" "$TMPDIR" "$XDG_CACHE_HOME"
