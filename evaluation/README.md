# Evaluation tooling

Runs the evaluation planned in [docs/EVALUATION.md](../docs/EVALUATION.md): two model families,
two fine-tuning runs each, watched by draftwatch on Explorer. This file gives the order of
operations; the design explains why.

| Path | What it does |
|---|---|
| `build_probes.py` | Builds the four probe files from pinned dataset revisions, with a SHA-256 manifest |
| `make_checkpoint0.py` | Builds `checkpoint-0`, the released target, as both runs' baseline |
| `build_training_data.py` | `select`: draws the trunk and branch data (CPU). `generate`: greedy responses with vLLM (GPU) |
| `train.py` | One fine-tuning run (trunk or branch) |
| `pilot_check.py` | The pilot's checks P2–P4 |
| `draftwatch.eval.yaml` | draftwatch's configuration for the runs |
| `cluster/` | Job scripts (`cpu.sbatch`, `generate.sbatch`, `train.sbatch`, `harness_direct.sbatch`) and the environment they share (`env.sh`) |
| `draftwatch_eval/`, `tests/` | The tested logic behind the scripts (standard library only) and its unit tests |

Everything below runs on Explorer as jobs, not on a login node. The job scripts source
`cluster/env.sh`, which puts the work and every cache in `/scratch/$USER/draftwatch-eval` (`$EVAL`)
and expects this repository at `/scratch/$USER/draftwatch` (`$REPO`) and the vLLM 0.31.0
environment at `/scratch/$USER/draftwatch-gpu/venv-cu129`. Do not source `env.sh` in a login
shell: it replaces `HOME` and `PATH`. CPU steps run as `sbatch $REPO/evaluation/cluster/cpu.sbatch
python <script> <args>` (written `cpu: <script> <args>` below), with network access. In
`draftwatch.eval.yaml`, replace `/scratch/user` with your own scratch directory.

## 1. Set up

1. `sbatch $REPO/evaluation/cluster/setup_train_env.sh` creates `$EVAL/venv-train`.
2. Download the four models at the revisions in the design's model table into
   `$EVAL/models/<name>` (for example `Qwen2.5-1.5B-Instruct`): `cpu: -c "from huggingface_hub
   import snapshot_download as d; d('<repo id>', revision='<sha>', local_dir='<dir>')"`.
3. `cpu: $REPO/evaluation/build_probes.py --out $EVAL/probes`.
4. For each family: `cpu: $REPO/evaluation/make_checkpoint0.py --model $EVAL/models/<target>
   --trunk $EVAL/runs/<family>-trunk`.
5. For each family: `cpu: $REPO/evaluation/build_training_data.py select --model
   $EVAL/models/<target> --probes $EVAL/probes --out $EVAL/data/<family>`, then
   `sbatch $REPO/evaluation/cluster/generate.sbatch $EVAL/models/<target> $EVAL/data/<family>
   trunk`. Each step prints how many candidates it needed.
6. `draftwatch validate --config <your copy of draftwatch.eval.yaml>`.

## 2. Pilot (P1–P4)

1. **P1.** Pin each run-1 baseline (`draftwatch baseline <family>-run1
   $EVAL/runs/<family>-trunk/checkpoint-0`), then `draftwatch submit <family>-run1` on the same
   checkpoint. When `draftwatch watch --once` has collected all eight reports, each job's time is
   the measured rate.
2. **P2.** For each family and each reference pool: `pilot_check.py subset --pool
   $EVAL/probes/<pool>.jsonl --out $EVAL/pilot/<pool>-subset.jsonl`. Then run
   `harness_direct.sbatch` on checkpoint-0 with that subset, and `pilot_check.py p2` against P1's
   report (under the state directory's `raw/`).
3. **P3.** Repeat P2's measurement on each other H200 node (`sinfo -p gpu-short -N -o "%N %G" |
   grep h200`, then `sbatch --nodelist=<node> harness_direct.sbatch ...`), and run
   `pilot_check.py p3`.
4. **P4.** Train a 100-step trunk (`train.sbatch --run trunk ... --out $EVAL/pilot/<family>-trunk
   --max-steps 100 --save-steps 100 --keep-state-at 100`). Then branch from it for 300 steps
   (`--run branch --data $EVAL/data/<family>/branch.jsonl --resume .../checkpoint-100
   --max-steps 400 --save-steps 125,150,200,300,400`). Measure the trunk checkpoint and all five
   branch checkpoints on the P2 subsets. List checkpoint-0, the trunk checkpoint, and branch
   checkpoints 125 and 400 in a `p4.json`, and run `pilot_check.py p4`; branch 150, 200, and 300
   show how fast the drop develops (the design's dense-saves condition). From the two training
   jobs' logs, record the steps per hour and check that 1,200 steps fit in the `gpu` partition's
   8 hours. If P4 fails, apply the design's fallbacks in their order, each into a new
   `$EVAL/pilot/` directory.

The full runs need a separate go-ahead, with the pilot's measured rates.

## 3. Runs

1. `draftwatch schedule --interval 15m`.
2. For each family, train the trunk: `sbatch train.sbatch --run trunk --model
   $EVAL/models/<target> --data $EVAL/data/<family>/trunk.jsonl --out $EVAL/runs/<family>-trunk`.
3. Once `draftwatch baseline <family>-run1` shows results for all four probes, pin run 2's
   baseline to the same checkpoint: `draftwatch baseline <family>-run2
   $EVAL/runs/<family>-trunk/checkpoint-0`.
4. When the trunk's checkpoint 600 is complete, train the branch: `sbatch train.sbatch --run
   branch --model $EVAL/models/<target> --data $EVAL/data/<family>/branch.jsonl --out
   $EVAL/runs/<family>-branch --resume $EVAL/runs/<family>-trunk/checkpoint-600`.
5. After each run, check `draftwatch status` and `alerts.log`. Copy the state directory,
   `$EVAL/data` (with `data.sha256.json`), `$EVAL/probes/probes.sha256.json`, and the pilot's
   reports off `/scratch`, which is purged monthly.
6. Delete a checkpoint only once it has results for all four probes. Copy trunk 600 and 1,200 and
   branch 1,200 of each family off `/scratch` for the noise study.
7. `train.py` refuses to write into a directory that already has trained checkpoints unless it
   resumes from one of them, and refuses a resume point without optimizer and scheduler state.
   Only trunk checkpoint 600 keeps that state, so an interrupted trunk restarts from scratch in a
   new directory, and an interrupted branch from trunk 600 in a new directory.
