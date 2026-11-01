#!/usr/bin/env python3
"""Fine-tunes one target for the evaluation runs (docs/EVALUATION.md, "Fine-tuning runs").

  train.py --run trunk  --model <target> --data <data>/trunk.jsonl  --out <trunk dir>
  train.py --run branch --model <target> --data <data>/branch.jsonl --out <branch dir> \
           --resume <trunk dir>/checkpoint-600

Full fine-tuning with weights and optimizer state in fp32 and bfloat16 autocast, AdamW, a constant
learning rate after a 30-step warm-up, 32 sequences per step, loss on response tokens only, and
seed 0. The data is pre-shuffled, so it is read in file order; every example is used once.
Checkpoints are saved on the run's schedule (draftwatch_eval.checkpoints.save_steps), finished,
and marked COMPLETE, so draftwatch measures only fully written checkpoints.

Checked against transformers v5.20.0 (src/transformers/):
  trainer.py:1068     Trainer._get_train_sampler(self, train_dataset=None), overridden below
  trainer.py:1929     on_step_end runs just before _maybe_log_save_evaluate, which saves when
                      control.should_save is set and then calls on_save
  trainer.py:3325     checkpoints are written to <output_dir>/checkpoint-<global_step>
  trainer.py:1690     resuming skips seen batches unless ignore_data_skip is set; it is set
                      for the branch only, whose data is new at the resume point
  modeling_utils.py:1404  from_pretrained takes dtype (torch_dtype is deprecated)
  tokenization_utils_base.py:3003  apply_chat_template returns a dict unless return_dict=False
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from draftwatch_eval.checkpoints import (  # noqa: E402
    check_output_dir, drop_steps_after, finalize, save_steps)
from draftwatch_eval.labels import IGNORE, check_first_batch, mask_prompt  # noqa: E402

MAX_STEPS = 1200
BATCH = 32
PER_DEVICE = 8
ACCUMULATION = BATCH // PER_DEVICE
WARMUP_STEPS = 30
BRANCH_START = 600
SEED = 0


def encode(tokenizer, example):
    user = [{"role": "user", "content": example["prompt"]}]
    prompt_ids = tokenizer.apply_chat_template(
        user, add_generation_prompt=True, tokenize=True, return_dict=False)
    full_ids = tokenizer.apply_chat_template(
        user + [{"role": "assistant", "content": example["response"]}],
        tokenize=True, return_dict=False)
    return {"id": example["id"], "input_ids": full_ids,
            "labels": mask_prompt(prompt_ids, full_ids)}


class Collator:
    """Pads a micro-batch, records its example ids, and checks the very first one."""

    def __init__(self, pad_id, expected_first):
        self.pad_id = pad_id
        self.expected_first = expected_first
        self.pending_ids = []
        self.loss_tokens = 0

    def __call__(self, features):
        import torch

        ids = [f["id"] for f in features]
        if self.expected_first is not None:
            check_first_batch(ids, self.expected_first)
            self.expected_first = None
        self.pending_ids.extend(ids)
        width = max(len(f["input_ids"]) for f in features)
        input_ids, labels, mask = [], [], []
        for f in features:
            pad = width - len(f["input_ids"])
            input_ids.append(f["input_ids"] + [self.pad_id] * pad)
            labels.append(f["labels"] + [IGNORE] * pad)
            mask.append([1] * len(f["input_ids"]) + [0] * pad)
            self.loss_tokens += sum(1 for t in f["labels"] if t != IGNORE)
        return {"input_ids": torch.tensor(input_ids), "labels": torch.tensor(labels),
                "attention_mask": torch.tensor(mask)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run", required=True, choices=("trunk", "branch"))
    parser.add_argument("--model", required=True, type=Path, help="the released target")
    parser.add_argument("--data", required=True, type=Path, help="trunk.jsonl or branch.jsonl")
    parser.add_argument("--out", required=True, type=Path, help="the run's output directory")
    parser.add_argument("--resume", type=Path, help="checkpoint to resume from")
    parser.add_argument("--lr", type=float, default=1e-5)
    parser.add_argument("--max-steps", type=int, default=MAX_STEPS,
                        help="stop earlier, for the pilot")
    parser.add_argument("--save-steps", type=lambda v: [int(x) for x in v.split(",")],
                        help="comma-separated steps to save at, for the pilot"
                             " (default: the run's schedule)")
    parser.add_argument("--keep-state-at", type=int, default=BRANCH_START,
                        help="the trunk step whose optimizer state the branch resumes from")
    args = parser.parse_args(argv)
    if args.run == "branch" and args.resume is None:
        parser.error("the branch resumes from the trunk's checkpoint 600: pass --resume")
    check_output_dir(args.out, resuming=args.resume is not None)
    resumed_at = (json.loads((args.resume / "trainer_state.json").read_text())["global_step"]
                  if args.resume else 0)

    import torch
    from torch.utils.data import Dataset, SequentialSampler
    from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer, TrainerCallback,
                              TrainingArguments)

    tokenizer = AutoTokenizer.from_pretrained(args.model)
    examples = [encode(tokenizer, json.loads(line))
                for line in args.data.read_text(encoding="utf-8").splitlines() if line]
    start = resumed_at if args.run == "branch" else 0
    needed = (args.max_steps - start) * BATCH
    if len(examples) < needed:
        raise SystemExit("%s has %d examples; steps %d-%d need %d"
                         % (args.data, len(examples), start + 1, args.max_steps, needed))

    class Examples(Dataset):
        def __len__(self):
            return needed

        def __getitem__(self, i):
            return examples[i]

    pad_id = tokenizer.pad_token_id if tokenizer.pad_token_id is not None \
        else tokenizer.eos_token_id
    # A trunk resumed after an interruption skips the batches it already trained on, so only a
    # fresh trunk and the branch (which starts its own data at step 601) begin at example 0.
    trunk_resume = args.run == "trunk" and args.resume is not None
    collator = Collator(pad_id, None if trunk_resume else examples[0]["id"])
    schedule = args.save_steps or save_steps(args.run)
    steps = set(s for s in schedule if s <= args.max_steps) | {args.max_steps}
    args.out.mkdir(parents=True, exist_ok=True)
    if trunk_resume:
        for name in ("examples_seen.txt", "loss_tokens.tsv"):
            drop_steps_after(args.out / name, resumed_at)  # those steps are trained again
    seen_log = (args.out / "examples_seen.txt").open("a", encoding="utf-8")
    tokens_log = (args.out / "loss_tokens.tsv").open("a", encoding="utf-8")

    class Schedule(TrainerCallback):
        def on_step_end(self, targs, state, control, **kwargs):
            for example_id in collator.pending_ids:
                seen_log.write("%d\t%s\n" % (state.global_step, example_id))
            tokens_log.write("%d\t%d\n" % (state.global_step, collator.loss_tokens))
            seen_log.flush()
            tokens_log.flush()
            collator.pending_ids = []
            collator.loss_tokens = 0
            control.should_save = state.global_step in steps
            return control

        def on_save(self, targs, state, control, **kwargs):
            step = state.global_step
            finalize(args.out / ("checkpoint-%d" % step),
                     keep_training_state=(args.run == "trunk" and step == args.keep_state_at),
                     final=(step == args.max_steps))

    class InOrder(Trainer):
        def _get_train_sampler(self, train_dataset=None):
            return SequentialSampler(train_dataset if train_dataset is not None
                                     else self.train_dataset)

    model = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.float32)
    trainer = InOrder(
        model=model,
        args=TrainingArguments(
            output_dir=str(args.out),
            max_steps=args.max_steps,
            per_device_train_batch_size=PER_DEVICE,
            gradient_accumulation_steps=ACCUMULATION,
            learning_rate=args.lr,
            lr_scheduler_type="constant_with_warmup",
            warmup_steps=WARMUP_STEPS,
            weight_decay=0.0,
            max_grad_norm=1.0,
            optim="adamw_torch",
            bf16=True,
            save_strategy="no",
            logging_steps=10,
            report_to="none",
            seed=SEED,
            ignore_data_skip=args.run == "branch",
            dataloader_num_workers=0,
            remove_unused_columns=False,
        ),
        train_dataset=Examples(),
        data_collator=collator,
        processing_class=tokenizer,
        callbacks=[Schedule()],
    )
    trainer.train(resume_from_checkpoint=str(args.resume) if args.resume else None)
    seen_log.close()
    tokens_log.close()

    used = [line.split("\t")[1] for line in
            (args.out / "examples_seen.txt").read_text(encoding="utf-8").splitlines()]
    if len(used) != needed or len(set(used)) != needed:
        raise SystemExit("used %d examples (%d distinct); expected each of %d exactly once"
                         % (len(used), len(set(used)), needed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
