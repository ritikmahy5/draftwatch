# Evaluation design

draftwatch is tested against fakes, recorded Slurm output, and real GPU runs on Explorer (an A100
acceptance run, an H200 end-to-end run, and H200 repeats). This document plans how it is evaluated
on real fine-tuning runs. The evaluation answers three questions:

1. **Operation.** Does draftwatch watch real fine-tuning runs unattended, measuring every selected
   checkpoint exactly once, and does its live detection flag a real regression on a probe that
   ground truth never saw?
2. **Detection.** How often do the detectors alarm when acceptance has not dropped, and how quickly
   do they catch a drop that has, as a function of the number of prompts in the probe?
3. **Noise model.** Do the paired bootstrap's intervals contain the true difference as often as
   they should?

The labels, thresholds, metric definitions, and detector configurations below are fixed before
any evaluation data exists. The pilot may change the training recipe, but only through the
fallbacks it lists, and only before the runs start. Calibration happens afterwards, with the
objective stated here. All thresholds are choices, not measurements, and every number that is a
measurement names the file it comes from.

## Design in one picture

```
step         0    100   ...   600  625 650 675 700   800   ...   1200
run 1  ──────●─────●──  ...  ──●─────────────────●─────●──  ...  ──●     trained on the target's own responses (control)
run 2                          └───●───●───●───●─────●──  ...  ──●     trained on UltraChat responses from step 600
```

Each model family gets one training trunk (run 1) and one branch (run 2) that starts from the
trunk's checkpoint 600. Up to step 600, the two runs are the same run. After it, they use the same
prompt distribution, settings, and response-length filter; what changes is who wrote the training
responses: the target itself (run 1) or another model (run 2). The branch saves densely just after
the switch, so a regression's onset can be located to within 25 steps.

Every checkpoint is measured by draftwatch on two 1,000-prompt **reference probes** and two
100-prompt **live probes**. Ground truth comes from the reference probes. Detection quality is
scored by replaying smaller probes, drawn as subsets of the reference prompts, through draftwatch's
own detector classes. The live probes share no prompts with the reference pools, so their live
alerts are an independent test of question 1.

## Model pairs

| Family | Target | Draft | Vocabulary | License |
|---|---|---|---|---|
| Qwen2.5 | `Qwen/Qwen2.5-1.5B-Instruct` @ `989aa7980e4cf806f80c7fef2b1adb7bc71aa306` | `Qwen/Qwen2.5-0.5B-Instruct` @ `7ae557604adf67be50417f59c2c2f167def9a775` | 151936 | Apache-2.0 |
| SmolLM2 | `HuggingFaceTB/SmolLM2-1.7B-Instruct` @ `31b70e2e869a7173562077fd711b654946d38674` | `HuggingFaceTB/SmolLM2-360M-Instruct` @ `a10cc1512eabd3dde888204e902eca88bddb4951` | 49152 | Apache-2.0 |

The draft is never trained. It stays the released model, as it would in practice while someone
fine-tunes the target.

Why these:
- The Qwen2.5 pair is the one already measured on GPUs
  (`src/test/resources/fixtures/real_10859767_*`).
- SmolLM2 differs from Qwen2.5 in organization, training data, tokenizer, and architecture code
  path in vLLM (`LlamaForCausalLM` versus `Qwen2ForCausalLM`).
- vLLM 0.31.0's `draft_model` method requires equal vocabulary sizes unless
  `use_heterogeneous_vocab` is set (`vllm/v1/spec_decode/draft_model.py`), and the draft uses the
  target's tokenizer (`vllm/config/speculative.py`). The SmolLM2 1.7B and 360M models satisfy
  this: their `tokenizer.json` files are byte-identical (SHA-256 `9ca9acdd…e47c`), and so are their
  chat templates.
- Llama 3.2 and Gemma 3 were considered. Both are gated on the Hugging Face Hub behind manual
  license approval, so every cluster download would need a token. SmolLM2 is not gated.
- Both targets are small enough to fine-tune fully on one GPU, so adapter checkpoints stay a
  separate study.

## Probes

| Probe | Prompts | Source @ revision | License |
|---|---|---|---|
| chat (reference) | 1,000 | `OpenAssistant/oasst2` @ `179dd21fc55192153d94adb0e0ce8f69e222bf75` | Apache-2.0 |
| chat-live | 100, disjoint from the chat pool | same | Apache-2.0 |
| code (reference) | 1,000 | `openai/openai_humaneval` @ `7dce6050a7d6d172f3cc5c32aa97f52fa1a2e544` and `google-research-datasets/mbpp` @ `4bb6404fdc6cacfda99d4ac4205087b89d32030c` (164 + 974 problems) | MIT; CC-BY-4.0 |
| code-live | 100 of the 138 code problems not in the pool | same | MIT; CC-BY-4.0 |

Each family measures all four probes, so a family has four probe ids (for example `qwen-chat`,
`qwen-chat-live`, `qwen-code`, `qwen-code-live`). The prompt files are shared between families; only
the draft differs.

Building the prompt files:
- One script builds all four files from the pinned revisions. It checks that every field it reads
  exists, fails if fewer prompts than needed pass its filters, and draws the samples with a fixed
  seed.
- The chat filters: `role == "prompter"`, `parent_id` null (first turn), `lang == "en"`,
  `synthetic == false`, `deleted == false`, `review_result == true`, at most 2,000 characters, and
  unique after lowercasing and collapsing whitespace.
- Each line is a `messages` object (MEASUREMENT_CONTRACT.md, "Reference backend"). Code problems
  are wrapped in one fixed instruction per source, which the script defines.
- The script records each file's SHA-256 and refuses to continue if a rebuild gives a different
  file.
- No probe prompt may appear among the fine-tuning prompts, compared after the same normalization.
  The training-data script checks this and fails on any overlap.

Every probe uses the decoding of the committed A100 run, the only setup verified end to end on a
GPU:

```yaml
decoding: { temperature: 0, max_new_tokens: 128, num_speculative_tokens: 4, dtype: bfloat16 }
estimator: token_weighted
seeds: [0]
```

Greedy decoding with one seed was observed to give identical counts across repeats on one H200
(MEASUREMENT_CONTRACT.md, "Comparability"). The pilot checks that this also holds across nodes and
prompt orders. If it does, the only noise left is which prompts were sampled, which is what the
evaluation studies. Sampled decoding is left to the noise study.

## Fine-tuning runs

Settings shared by every run:

| Setting | Value |
|---|---|
| Method | Full fine-tuning. Weights and optimizer state in fp32, forward and backward passes in bfloat16 autocast. (With bfloat16 weights, updates of this size are mostly rounded away.) |
| Attention | Eager (transformers' own implementation). In the pilot, PyTorch's SDPA kernel, the default, gave NaN gradients on a padded Qwen2.5 batch at step 23; eager attention on the same batch, and either kernel on each of its examples alone, did not. |
| Optimizer | AdamW, weight decay 0, gradient clipping 1.0. The script stops at the first step after which any weight is not finite, so a diverged run never saves a checkpoint. |
| Learning rate | 1e-5, constant after a 30-step linear warm-up; the pilot may change it, for both runs together |
| Batch | 32 sequences per step, no packing |
| Examples | Single-turn (prompt, response) pairs in the target's own chat template. The response is at most 768 tokens and the whole example at most 1,024; longer ones are dropped, not truncated. Every example is used exactly once, and the script logs example ids to prove it. |
| Loss | Response tokens only. The script checks the loss mask on the first batch and fails if any prompt token carries loss. It reports loss tokens per step, so the two runs' training signal can be compared. |
| Seed | 0 |
| Markers | `COMPLETE` written into a checkpoint after its save finishes; `FINAL` in the last one |

**Run 1 (trunk, the control).** Steps 1–1,200 on 38,400 examples. The prompts are first user turns
from UltraChat (`HuggingFaceH4/ultrachat_200k` @ `8049631c405ae6576f93f445c6b8166f76f5505a`, MIT,
split `train_sft`). The responses are the unmodified target's own greedy outputs, generated with
vLLM before training, without speculative decoding, up to 768 new tokens. Responses that hit the
length limit instead of ending are dropped, so the model is never trained to not stop; enough
prompts are generated that 38,400 examples remain. Training on its own greedy outputs should leave
the target's argmax choices, and so greedy acceptance, roughly unchanged. Ground truth decides
whether it did. Saves every 100 steps.

**Run 2 (branch, the change point).** Steps 601–1,200 on exactly 19,200 UltraChat
(first user turn, first assistant reply) pairs, with prompts disjoint from run 1's, under the same
length filter. It resumes from the trunk's checkpoint 600 with its optimizer and scheduler state,
so there is no second warm-up. The Hugging Face Trainer fast-forwards through "already seen"
batches when it resumes unless `ignore_data_skip` is set (transformers v5.20.0,
`Trainer._init_training_state`), so it is set, and the script checks that the first branch batch
starts with the first branch example. Saves every 25 steps through step 800, then every 100.

**Checkpoint 0.** The released target is the baseline of both runs. It is built as
`checkpoint-0` in the trunk's directory from the weight, config, tokenizer, and generation-config
files only, followed by a `COMPLETE` marker. The released SmolLM2 repository also ships
`trainer_state.json` (`global_step: 1431`), `training_args.bin`, `onnx/`, and `runs/`. draftwatch
reads the step from `trainer_state.json` before the directory name, and counts `*.bin` files as
weights, so those files are left out.

Every checkpoint directory must load on its own in vLLM. Only the trunk's checkpoint 600 keeps
optimizer state, because the branch resumes from it.

## How draftwatch watches the runs

One configuration and one state directory hold eight probes (four per family) and four targets.
The run-1 targets are listed before the run-2 targets.
- Executor `slurm`, partition `gpu-short`, `gres: "gpu:h200:1"`, so a series never switches GPU
  model (SPEC.md F5, "Changed GPU"). The job time limit is set from P1's measured rate.
- Triggers: the default chain, but with `max_pending: 2` per target. Explorer's `gpu-short` QoS
  accepts 8 submitted jobs per user, and four targets with two pending jobs each reach exactly that.
  No other `gpu-short` jobs run on the account during the runs.
- `completion: { marker: COMPLETE }`.
- Detectors, listed explicitly (a `detectors:` list replaces the default): B0, A, and T from
  "Scoring the detectors".
- `draftwatch schedule --interval 15m` runs the watch as a CPU job. Training jobs go separately to
  the `gpu` partition, as a user's training would.
- Baselines are pinned explicitly, in this order:
  1. `draftwatch baseline <family>-run1 <trunk>/checkpoint-0`, before the trunk starts.
  2. After `draftwatch baseline <family>-run1` shows a result for all four of the family's probes:
     `draftwatch baseline <family>-run2 <trunk>/checkpoint-0`, before the branch's first checkpoint
     appears.

  Each run-2 target watches only its branch directory. Detection finds a baseline's result by
  checkpoint fingerprint (`DetectionService`), so run 2 reuses run 1's measurements of checkpoint
  0. Pinning in this order matters: if run 2 measured checkpoint 0 itself, run 1 would pin a later
  checkpoint, and run 2's deferred detections would never be replayed, because replay runs only for
  the target that stored the baseline's result.
- A submission that Slurm refuses is alerted, and the checkpoint is submitted again with
  `draftwatch submit`. `draftwatch status` is checked after each training run.

Explorer's limits, read with `scontrol` and `sacctmgr` on 2026-10-15: `gpu-short` allows 2 hours
per job, 2 GPUs and 2 running jobs per user, and 8 submitted jobs per user; `gpu` allows 8 hours
and 4 GPUs per user. Both partitions share the same four nodes with eight H200s each.

## Ground truth

For every checkpoint c and reference probe, the reference difference is

```
Δ_ref(c) = alpha(c) − alpha(checkpoint 0)
```

on all 1,000 prompts, with a 95% interval [L, U] from draftwatch's own `PairedBootstrap` (a
two-sided percentile interval over prompts resampled in pairs; 2,000 resamples, seed 0). `tau` is
reported alongside but not scored.

The practical threshold is δ = 0.02 in `alpha` (about 3.5% of the A100 fixture's `alpha` of
0.5760869565217391, in `real_10859767_report.json`). Labels require both the point estimate and the
interval to agree:

| Label | Condition |
|---|---|
| REGRESSED | Δ_ref ≤ −δ and U < −δ/2 |
| NOT_REGRESSED | Δ_ref ≥ −δ/2 and L > −δ |
| AMBIGUOUS | anything else; reported, but excluded from the rates |

The interval condition replaces a fixed precision requirement. From the A100 fixture, one prompt's
`alpha` has a standard deviation of about 0.13, which gives a half-width of about 0.008 for one
1,000-prompt measurement and about 0.0115 for a paired difference of unrelated checkpoints. A
fixed requirement of δ/4 = 0.005 would therefore have failed exactly for the diverged checkpoints
that matter. P4 estimates the actual paired half-width before the runs.

δ sets the labels, the delay onset, P4's pass conditions, and A's threshold. Detection and alarm
rates are also reported per checkpoint against Δ_ref, so the runs can be re-scored for another δ
without new measurements.

## Scoring the detectors

**Probes as subsets.** For each probe size N in {25, 50, 100, 200}, 500 subsets of the 1,000
reference prompts are drawn without replacement, with a fixed seed. Each subset plays the role of
one user's fixed probe: it is applied unchanged to every checkpoint of a run, so every comparison
is paired, as it is in draftwatch. A subset's result at a checkpoint is computed from the stored
per-prompt counts of those prompts. That is valid only if a prompt's counts do not depend on which
other prompts were measured with it, which P2 checks.

**draftwatch's own code.** A scoring tool, in its own Gradle source set with no new dependencies,
builds measurements from subsets of stored reports and passes them through the same
`RegressionDetector` implementations that `watch` uses. To be faithful it:
- gives each subset its own probe hash and prompt-set SHA-256, because Comparability compares
  probe hashes, so different subsets are never comparable and one subset is comparable with itself
  across checkpoints;
- computes each subset's totals and aggregate with `MetricCalculator`, because `absolute_drop` and
  `trend` read the aggregate;
- keeps prompts in a fixed order, because the bootstrap pairs prompts by position;
- gives every subset measurement a unique job id, because `trend` drops duplicates by job id;
- leaves positional counts empty, because they are not stored per prompt and no detector reads
  them;
- maps the cluster paths in stored provenance to the archived copy, and lists every result file it
  read with its SHA-256. Every number in the write-up comes from its output.

**Detector configurations**, fixed now:

| Id | Configuration |
|---|---|
| B0 | `paired_bootstrap: { metric: alpha, confidence: 0.95, min_effect: 0.0 }` (the default) |
| B1 | `paired_bootstrap: { metric: alpha, confidence: 0.95, min_effect: 0.01 }` |
| A | `absolute_drop: { metric: alpha, max_drop: 0.02 }` |
| T | `trend: { metric: alpha, window: 4, max_slope: -0.005 }` |

`noise_floor` needs an external `sigma`, so it is scored after the noise study supplies one.

T is scored on the same history `watch` gives it: run 1's own checkpoints for run 1, and only the
branch checkpoints for run 2, because history is kept per target. With the dense branch saves, T
reaches its first run-2 verdict at step 700. A verdict of insufficient data counts as no flag. T
measures a slope, so it cannot see a drop that happened before its window, and its run-2 results
are reported with that caveat rather than as a failure of the labels.

**Metrics.** Compared checkpoints are every checkpoint except checkpoint 0: steps 100–1,200 of run 1
(12) and the branch checkpoints of run 2 (12). Trunk checkpoints belong to run 1 only. Each metric is
reported per family, probe, configuration, and N.

| Metric | Definition |
|---|---|
| Alarm rate per checkpoint | For each compared checkpoint: the fraction of subsets that flag it, shown against its Δ_ref. This is the primary result; the summaries below are derived from it. |
| False-alarm rate | Pooled over NOT_REGRESSED checkpoints. B0 tests for any drop, so its alarms on small real drops are listed separately, by Δ_ref. |
| Detection rate | Pooled over REGRESSED checkpoints. |
| Run false-alarm rate | The fraction of subsets that flag at least one NOT_REGRESSED checkpoint of run 1. The fraction that flag any run-1 checkpoint is reported next to it, because a user sees AMBIGUOUS checkpoints too. |
| Detection delay | On run 2, in training steps, from the onset (the first branch checkpoint labeled REGRESSED) to the first flagged checkpoint at or after it. Reported as the median and the miss fraction, counting a subset that never flags at or after the onset as an infinite delay. Earlier flags are counted separately: "early" on AMBIGUOUS checkpoints, false alarms on NOT_REGRESSED ones. Not scored if no branch checkpoint is REGRESSED, or if any trunk checkpoint up to 600 already is (the control has then failed, which is reported). |
| Interval coverage | For B0: the fraction of subset intervals [L, U] that contain Δ_ref. Subsets are drawn without replacement from a finite pool, so the bootstrap's intervals are wider than the subset's true spread. The expected coverage for a nominal 95% is therefore 95.3% at N = 25, 95.6% at 50, 96.1% at 100, and 97.2% at 200. Coverage is compared against these values, not against 95%. |
| Secondary: drift against the control | At steps 700–1,200, where both runs have a checkpoint: run 2's `alpha` minus run 1's at the same step, on the reference probes. |
| Cost | GPU time per measurement, from `wall_clock_seconds` and the job's elapsed time in `sacct`. Measured at N = 100 (live probes), 200 (pilot), and 1,000 (reference); smaller sizes are extrapolated and labeled as such. |

**What the rates can and cannot say.** The 500 subsets measure sampling error given one prompt pool,
one training seed, and 12 checkpoints per run. Summary rates therefore come with a bootstrap over
checkpoints, not a binomial interval over subsets, and the claims are scoped to these runs. Drawing
subsets from the finite pool also makes every rate slightly optimistic: the subsets vary less than
independent probes would, so detection reads a little high and false alarms a little low, most at
N = 200.

**Calibration later.** Each detector's parameters are tuned on the Qwen2.5 runs to minimize the
median delay subject to a run false-alarm rate of at most 10%, at N = 100. Qwen2.5 results after
tuning are in-sample. SmolLM2 is the held-out family, on which the tuned defaults are reported
unchanged.

## Pilot

These checks run before the training runs, on H200s, and both families do every check.

| Check | How | Passes if | If it fails |
|---|---|---|---|
| P1 Baseline and rate | Set up the configuration, then `draftwatch submit` checkpoint 0 for all four probes. These are the real baseline measurements. | Each measurement fits in a 1-hour job | Raise the job time (gpu-short allows 2 hours) and re-budget |
| P2 Order independence | Run the harness directly on a 200-prompt subset of each reference pool in shuffled order, on checkpoint 0. P1's warm-up prompt (pool prompt 0) is placed after the first line. | Every prompt's (steps, proposed, accepted) equals its counts in P1 | Subsets are invalid. vLLM's prefix caching, on by default in 0.31.0 (`vllm/config/cache.py`), is the first suspect. The fix is a harness change, verified on a GPU, followed by a new P1; it is not a change to scoring. |
| P3 Nodes | Repeat P2 on each of the other three H200 nodes (`--nodelist`) | Every report equals P2's in every count | Measurements on different nodes are not interchangeable; the comparability key must include the node before the runs |
| P4 Recipe | Train 100 trunk steps from checkpoint 0, then branch for 300 steps from that checkpoint, resuming as run 2 does. Save branch steps 25, 50, 100, 200, and 300. Measure the trunk checkpoint and the five branch checkpoints on P2's subsets. | **Control:** the trunk checkpoint's Δ ≥ −δ/2. **Strong enough:** at branch step 300, U < −δ/2 on at least one probe. **Gradual enough:** at branch step 25, U ≥ −δ on both probes. | Control or gradual fails: learning rate 5e-6 for both runs, then repeat P4. Strong enough fails: learning rate 2e-5 for both runs, then repeat P4. If it still fails, the branch trains on the other family's greedy responses to its UltraChat prompts instead (Qwen2.5 on SmolLM2's, SmolLM2 on Qwen2.5's), generated and filtered exactly as run 1's responses are, then P4 is repeated. If that fails too, the runs go ahead, and the success criterion that a planted regression is flagged is reported as not met; detection is then scored only on whatever REGRESSED checkpoints exist. |

P4 also checks the branch's resume (the first-batch check), measures training throughput, and
confirms that a 1,200-step run fits in the `gpu` partition's 8 hours. From the pair of 200-prompt
measurements at branch step 300 it estimates the paired half-width at 1,000 prompts, scaling by
√(200/1000).

P2–P4 run the harness directly rather than through draftwatch, because they need node pinning and
throwaway subset files. Their reports pass the harness's own contract validation and are archived
as evidence.

## Compute budget

The planning rate is the committed A100 run: 32.5 s for 21 generation calls (20 prompts plus the
warm-up) at these decoding settings (`real_10859767_report.json`, `wall_clock_seconds`, which
excludes model loading), so about 1.55 s per prompt. It is an estimate, not a bound: the H200 was
faster in the end-to-end run, but SmolLM2's draft has 32 layers to Qwen2.5-0.5B's 24, and one
prompt at a time is latency-bound. P1 replaces it with measured rates.

| Item | Count | GPU-hours at the planning rate |
|---|---|---|
| Reference measurements | 2 families × 25 checkpoints × 2 probes × 1,000 prompts | about 43 |
| Live measurements | 2 families × 25 checkpoints × 2 probes × 100 prompts | about 4.3 |
| Model loading | 200 measurement jobs | timed in P1 |
| Pilot (P2–P4) | both families | about 3 |
| Response generation and training | both families | timed in P4 |

A family has 25 measured checkpoints: 13 in run 1 (0, 100, …, 1,200) and 12 in run 2 (625, 650,
675, 700, 725, 750, 775, 800, 900, 1,000, 1,100, 1,200). With two GPUs at a time on `gpu-short`, the
measurements take at least about a day of wall time.

## Data handling

- Explorer purges `/scratch` monthly (Explorer documentation, "Scratch Directory Purge"). The state
  directory is copied off the cluster after each run and at least weekly.
- The generated run-1 responses cannot be regenerated byte for byte, so they are archived with the
  results, not rebuilt. Probe files and UltraChat selections are rebuilt from the pinned sources
  and checked by SHA-256.
- A checkpoint is deleted only after a result exists for all four of its probes. Trunk 600 and
  1,200 and branch 1,200 of each family are kept for the noise study, copied off `/scratch`.
- The noise study's re-measurements use a separate state directory. In this one they would become
  the latest comparable result of the baseline, which detection then uses.
- The evaluation's raw reports and the scoring tool's output are committed under
  `evaluation/results/`, with cluster paths replaced by the placeholder `/scratch/user/...` used by
  the existing fixtures, so every number in the write-up can be traced in the repository.

## Limits

- Two small model families (at most 1.7B parameters), chain drafts, and one inference backend.
- Greedy decoding with 128 new tokens measures acceptance near the start of a response only.
- Supervised fine-tuning only; preference tuning and RL may shift the distribution differently.
- One training seed per recipe and one prompt pool per probe: whether a regression reproduces
  across training seeds is not tested.
- Run 1's control is self-generated data, chosen because it is expected to be stable, not because
  it is typical.
- One GPU model. Results on other GPUs are not comparable by design.

## Decisions and when to revisit them

| Decision | Revisit if |
|---|---|
| SmolLM2 as the second family | A larger target is needed; full fine-tuning on one GPU then no longer fits, and the adapter study's setup applies |
| Greedy decoding, 128 new tokens, k = 4 | The noise study shows that sampled decoding behaves differently enough to matter for the defaults |
| 1,000-prompt reference pools | Most branch checkpoints come out AMBIGUOUS |
| δ = 0.02 in `alpha` | Users report a different size of drop that matters to them; the per-checkpoint results allow re-scoring without new runs |
| Subsets instead of separate small-probe measurements | P2 or P3 fails |
| UltraChat responses as the shift, learning rate 1e-5 | P4 fails; its fallbacks (a higher learning rate, then another family's responses) are listed above |
| Dense branch saves through step 800 | P4 shows that the drop develops over hundreds of steps, not tens |
