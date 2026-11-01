"""Checkpoint layout for the evaluation runs (docs/EVALUATION.md, "Fine-tuning runs").

draftwatch discovers a checkpoint once its COMPLETE marker exists (completion: { marker:
COMPLETE }), reads its step from trainer_state.json before the directory name, and counts *.bin
files as weights. So a checkpoint is finished in full before COMPLETE is written, and
checkpoint-0 carries only the files a model needs.
"""

import shutil
from pathlib import Path

COMPLETE = "COMPLETE"
FINAL = "FINAL"
TRAINING_STATE = ("optimizer.pt", "scheduler.pt", "scaler.pt")
CHECKPOINT0_FILES = ("model.safetensors.index.json", "config.json", "generation_config.json",
                     "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
                     "vocab.json", "merges.txt")


def save_steps(run):
    """The steps at which ``run`` ("trunk" or "branch") saves a checkpoint."""
    if run == "trunk":
        return list(range(100, 1201, 100))
    if run == "branch":
        return list(range(625, 801, 25)) + list(range(900, 1201, 100))
    raise ValueError("unknown run %r; expected 'trunk' or 'branch'" % run)


def finalize(checkpoint, keep_training_state, final):
    """Removes the optimizer, scheduler, scaler, and RNG state unless ``keep_training_state``,
    writes FINAL if ``final``, and writes COMPLETE last."""
    checkpoint = Path(checkpoint)
    if not keep_training_state:
        for path in list(checkpoint.glob("rng_state*.pth")) + [
                checkpoint / name for name in TRAINING_STATE]:
            if path.exists():
                path.unlink()
    if final:
        (checkpoint / FINAL).touch()
    (checkpoint / COMPLETE).touch()


def build_checkpoint0(model_dir, trunk_dir):
    """Copies the released model's weight, config, and tokenizer files into
    ``trunk_dir/checkpoint-0`` and writes COMPLETE. Raises ValueError without safetensors
    weights."""
    model_dir = Path(model_dir)
    weights = sorted(model_dir.glob("*.safetensors"))
    if not weights:
        raise ValueError("%s has no *.safetensors weights" % model_dir)
    out = Path(trunk_dir) / "checkpoint-0"
    out.mkdir(parents=True, exist_ok=False)
    for path in weights + [model_dir / name for name in CHECKPOINT0_FILES]:
        if path.exists():
            shutil.copy2(path, out / path.name)
    (out / COMPLETE).touch()
    return out


def check_output_dir(out, resuming):
    """Raises ValueError if ``out`` already holds checkpoints and this is not a resume, so two
    runs can never write into one directory."""
    existing = sorted(p.name for p in Path(out).glob("checkpoint-*"))
    if existing and not resuming:
        raise ValueError("%s already has %s; pass --resume to continue that run"
                         % (out, ", ".join(existing)))


def drop_steps_after(log, step):
    """Keeps only the lines of a per-step log (first field: the step) up to ``step``, so a
    resumed run's logs do not count the steps it trains again."""
    log = Path(log)
    if log.exists():
        lines = log.read_text(encoding="utf-8").splitlines(keepends=True)
        log.write_text("".join(line for line in lines if int(line.split("\t", 1)[0]) <= step),
                       encoding="utf-8")
