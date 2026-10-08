#!/usr/bin/env python3
"""Records real Slurm output for draftwatch's fixtures, and checks sbatch inside a job.

Run it once on the target cluster, as a small CPU-only job of its own: Explorer kills
long-running processes on login nodes (a first attempt there was killed within a minute):

    sbatch --time=01:00:00 --mem=1G --cpus-per-task=1 --output=recorder-%j.log \
        --wrap "python3 -u scripts/record_slurm_fixtures.py --out slurm-fixtures"

Options: [--partition short] [--account lab] [--history-days 365] [--poll 20]
[--max-minutes 40] [--history-only] (record only the history files, submitting nothing).

It submits a few tiny CPU-only jobs (1 CPU, at most 100 MB, at most a few minutes each),
polls each one with exactly the squeue and sacct arguments draftwatch uses, and writes one
file per job, real_<job-id>_<scenario>.json, in the format of
src/test/resources/fixtures/slurm/. It also:
- asks a job to call sbatch, to check that a job may submit jobs (sbatch_inside_job.json);
- reads your own sacct history for states a user cannot cause, such as NODE_FAIL and PREEMPTED
  (real_<job-id>_history_<state>.json);
- records the Slurm settings draftwatch's Slurm handling depends on (recording.json).

Every file holds output exactly as the commands printed it. History files contain your past
job ids and times, and "CANCELLED by <uid>" names a numeric uid; review them before
committing. Every job this script submitted is cancelled when it exits, even on Ctrl-C or when Slurm
stops it (SIGTERM).

    python3 scripts/record_slurm_fixtures.py --print-argv 4242

prints the query arguments as JSON; draftwatch's tests compare them with the Java code's.

Standard library only; Python 3.6 or newer, since login nodes often have an old python3.
"""

import argparse
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

# --- the queries, exactly as SlurmCli defines them ---------------------------------------

SQUEUE_FORMAT = "%i|%T|%S"
SACCT_FORMAT = "JobIDRaw,State,ExitCode,Start,End"
TIME_FORMAT = "%Y-%m-%dT%H:%M:%S%z"
QUERY_SET = {"SLURM_TIME_FORMAT": TIME_FORMAT}
QUERY_UNSET = ["SQUEUE_FORMAT", "SQUEUE_FORMAT2"]


def squeue_argv(job_id):
    return ["squeue", "--noheader", "--states=all", "--format=" + SQUEUE_FORMAT,
            "--jobs=" + job_id]


def sacct_argv(job_id):
    return ["sacct", "--noheader", "--parsable2", "--allocations", "--format=" + SACCT_FORMAT,
            "--jobs=" + job_id]


# The terminal states of squeue.html/sacct.html "JOB STATE CODES".
TERMINAL = {"BOOT_FAIL", "CANCELLED", "COMPLETED", "DEADLINE", "FAILED", "NODE_FAIL",
            "OUT_OF_MEMORY", "PREEMPTED", "TIMEOUT"}

# States a user cannot cause on demand; looked up in the user's own history.
HISTORY_STATES = "BF,DL,NF,OOM,PR,RQ,TO"

# --- the jobs ----------------------------------------------------------------------------

SBATCH_CHILD = "draftwatch-fixture-sbatch-child"

SCENARIOS = [
    # name, sbatch options beyond the defaults, script body, action when observed
    ("completed", [], "sleep 20\nexit 0\n", None),
    ("failed_exit_3", [], "sleep 5\nexit 3\n", None),
    ("cancelled_pending", ["--begin=now+3600"], "exit 0\n", ("PENDING", "scancel")),
    ("cancelled_running", [], "sleep 300\n", ("RUNNING", "scancel")),
    ("timeout", ["--time=1"], "sleep 600\n", None),
    ("out_of_memory", ["--mem=64M"],
     "python3 -c 'b = b\"x\" * (1024 * 1024 * 1024)'\n", None),
    ("requeued", ["--requeue"], "sleep 60\n", ("RUNNING", "requeue")),
]


def defaults(name, out, args):
    options = {
        "--job-name": "draftwatch-fixture-" + name,
        "--output": os.path.join(out, "logs", name + "-%j.out"),
        "--error": os.path.join(out, "logs", name + "-%j.err"),
        "--time": "5",
        "--mem": "100M",
        "--ntasks": "1",
        "--cpus-per-task": "1",
    }
    if args.partition:
        options["--partition"] = args.partition
    if args.account:
        options["--account"] = args.account
    return options


def sbatch_options(options, extra):
    merged = dict(options)
    flags = []
    for e in extra:
        if "=" in e:
            key, value = e.split("=", 1)
            merged[key] = value
        else:
            flags.append(e)
    return [k + "=" + v for k, v in merged.items()] + flags


def sbatch_inside_job_script(args):
    """A job that submits a held child job from inside itself."""
    child_options = ["--parsable", "--begin=now+3600", "--time=1", "--mem=10M",
                     "--job-name=" + SBATCH_CHILD, "--output=/dev/null"]
    if args.partition:
        child_options.append("--partition=" + args.partition)
    if args.account:
        child_options.append("--account=" + args.account)
    return ("echo \"parent job $SLURM_JOB_ID on $(hostname)\"\n"
            "child=$(printf '#!/bin/sh\\ntrue\\n' | sbatch " + " ".join(child_options) + ")\n"
            "echo \"sbatch exit status: $?\"\n"
            "echo \"child: $child\"\n")


# --- running commands --------------------------------------------------------------------

def run(argv, query=False):
    env = dict(os.environ)
    if query:
        for name in QUERY_UNSET:
            env.pop(name, None)
        env.update(QUERY_SET)
    p = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
                       stdin=subprocess.DEVNULL, universal_newlines=True, timeout=120)
    return {"command": argv, "exit_code": p.returncode, "stdout": p.stdout,
            "stderr": p.stderr}


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sacct_state(result):
    for line in result["stdout"].splitlines():
        fields = line.split("|")
        if len(fields) == 5:
            return fields[1].split(" ")[0].rstrip("+")
    return None


def in_squeue(result, job_id):
    if result["exit_code"] != 0:
        return False
    return any(line.split("|")[0].strip() == job_id
               for line in result["stdout"].splitlines() if line.strip())


def squeue_state(result, job_id):
    for line in result["stdout"].splitlines():
        fields = line.split("|")
        if len(fields) == 3 and fields[0].strip() == job_id:
            return fields[1].strip()
    return None


def observe(job_id):
    return {"at": now(), "squeue": run(squeue_argv(job_id), query=True),
            "sacct": run(sacct_argv(job_id), query=True)}


def same(a, b):
    return all(a[k] == b[k] for k in ("squeue", "sacct"))


# --- main --------------------------------------------------------------------------------

def stop(signum, frame):
    """Turns SIGTERM, which Slurm sends before killing a job, into an exit that runs cleanup."""
    raise SystemExit("stopped by signal %d" % signum)


def main():
    signal.signal(signal.SIGTERM, stop)
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", help="directory for the recorded files")
    parser.add_argument("--partition")
    parser.add_argument("--account")
    parser.add_argument("--history-days", type=int, default=365)
    parser.add_argument("--poll", type=int, default=20, help="seconds between observations")
    parser.add_argument("--max-minutes", type=int, default=40)
    parser.add_argument("--history-only", action="store_true",
                        help="record only the history files; submit nothing")
    parser.add_argument("--print-argv", metavar="JOB_ID",
                        help="print the query arguments for JOB_ID as JSON and exit")
    args = parser.parse_args()
    if args.print_argv:
        print(json.dumps({"squeue": squeue_argv(args.print_argv),
                          "sacct": sacct_argv(args.print_argv),
                          "set": QUERY_SET, "unset": QUERY_UNSET}))
        return 0
    if not args.out:
        parser.error("--out is required")
    out = os.path.abspath(args.out)
    os.makedirs(os.path.join(out, "logs"), exist_ok=True)
    os.makedirs(os.path.join(out, "scripts"), exist_ok=True)

    try:
        versions = {c: run([c, "--version"]) for c in ("sbatch", "squeue", "sacct", "scontrol")}
    except FileNotFoundError as e:
        print("cannot run %s: run this script on a login node of the cluster" % e.filename,
              file=sys.stderr)
        return 2
    version = versions["sbatch"]["stdout"].strip() or "unknown version"
    source = ("recorded on " + socket.getfqdn() + " by scripts/record_slurm_fixtures.py at "
              + now() + " (" + version + ")")
    config = run(["scontrol", "show", "config"])
    wanted = ("ClusterName", "SLURM_VERSION", "MinJobAge", "JobRequeue", "PreemptMode",
              "PreemptType", "JobFileAppend", "OverTimeLimit")
    settings = {}
    for line in config["stdout"].splitlines():
        key = line.split("=", 1)[0].strip()
        if key in wanted and "=" in line:
            settings[key] = line.split("=", 1)[1].strip()

    jobs = []  # dicts: name, id, sbatch, observations, action, actions, done
    scenarios = [] if args.history_only else SCENARIOS + [
        ("sbatch_inside_job", [], sbatch_inside_job_script(args), None)]
    try:
        for name, extra, body, action in scenarios:
            script = os.path.join(out, "scripts", name + ".sh")
            with open(script, "w") as f:
                f.write("#!/bin/sh\n" + body)
            argv = (["sbatch", "--parsable"] + sbatch_options(defaults(name, out, args), extra)
                    + [script])
            result = run(argv)
            job = {"name": name, "sbatch": result, "observations": [], "action": action,
                   "actions": [], "done": False, "terminal_since": None}
            if result["exit_code"] == 0 and re.match(r"^\d+", result["stdout"].strip()):
                job["id"] = result["stdout"].strip().split(";")[0]
                print("submitted", name, "as job", job["id"])
            else:
                job["id"] = None
                job["done"] = True
                print("sbatch refused", name + ":", result["stderr"].strip(), file=sys.stderr)
            jobs.append(job)

        deadline = time.time() + 60 * args.max_minutes
        while time.time() < deadline and not all(j["done"] for j in jobs):
            for job in jobs:
                if job["done"]:
                    continue
                o = observe(job["id"])
                obs = job["observations"]
                # Keep the first and last of every run of identical observations.
                if len(obs) >= 2 and same(obs[-1], o) and same(obs[-2], o):
                    obs[-1] = o
                else:
                    obs.append(o)
                state = squeue_state(o["squeue"], job["id"])
                if job["action"] and state == job["action"][0]:
                    verb = job["action"][1]
                    argv = (["scancel", job["id"]] if verb == "scancel"
                            else ["scontrol", "requeue", job["id"]])
                    r = run(argv)
                    r["at"] = now()
                    job["actions"].append(r)
                    print(job["name"] + ":", " ".join(argv), "->", r["exit_code"])
                    job["action"] = None
                if sacct_state(o["sacct"]) in TERMINAL and not in_squeue(o["squeue"], job["id"]):
                    job["done"] = True
                    print(job["name"] + ": finished as", sacct_state(o["sacct"]))
            time.sleep(args.poll)
    finally:
        for job in jobs:
            if job.get("id") and not job["done"]:
                run(["scancel", job["id"]])
                print("cancelled unfinished job", job["id"], file=sys.stderr)

    written = []
    for job in jobs:
        if not job.get("id"):
            continue
        doc = {"source": source, "note": "scenario " + job["name"], "job_id": job["id"],
               "submitted_with": job["sbatch"], "actions": job["actions"],
               "finished": job["done"], "observations": job["observations"]}
        path = os.path.join(out, "real_%s_%s.json" % (job["id"], job["name"]))
        write(path, doc)
        written.append(path)

    inside_job = sbatch_inside_job_answer(jobs, out, source)
    if inside_job:
        write(os.path.join(out, "sbatch_inside_job.json"), inside_job)

    start = (datetime.now(timezone.utc) - timedelta(days=args.history_days)).strftime(
        "%Y-%m-%d")
    user = os.environ.get("USER", "")
    # --state selects jobs by their state during [--starttime, --endtime]. On Explorer
    # (Slurm 23.11.6) this query found no jobs without --endtime and the expected ones with it.
    history = run(["sacct", "--noheader", "--parsable2", "--allocations", "--user=" + user,
                   "--starttime=" + start, "--endtime=now", "--state=" + HISTORY_STATES,
                   "--format=" + SACCT_FORMAT], query=True)
    latest = {}
    for line in history["stdout"].splitlines():
        fields = line.split("|")
        if len(fields) == 5 and re.match(r"^\d+$", fields[0]):
            latest[fields[1].split(" ")[0]] = fields[0]
    for state, job_id in sorted(latest.items()):
        doc = {"source": source, "note": "from the user's sacct history, state " + state,
               "job_id": job_id, "observations": [observe(job_id)]}
        path = os.path.join(out, "real_%s_history_%s.json" % (job_id, state.lower()))
        write(path, doc)
        written.append(path)

    write(os.path.join(out, "recording.json"),
          {"source": source, "versions": versions, "settings": settings,
           "history_query": history, "files": [os.path.basename(p) for p in written]})
    print("\nwrote %d fixture files to %s" % (len(written), out))
    if inside_job:
        print("sbatch inside a job:", inside_job["answer"])
    return 0


def sbatch_inside_job_answer(jobs, out, source):
    job = next((j for j in jobs if j["name"] == "sbatch_inside_job" and j.get("id")), None)
    if job is None:
        return None
    log = os.path.join(out, "logs", "sbatch_inside_job-%s.out" % job["id"])
    err = os.path.join(out, "logs", "sbatch_inside_job-%s.err" % job["id"])
    text = open(log).read() if os.path.exists(log) else ""
    errors = open(err).read() if os.path.exists(err) else ""
    status = re.search(r"sbatch exit status: (\d+)", text)
    child = re.search(r"child: (\d+)", text)
    child_record = None
    if child:
        child_record = observe(child.group(1))
        child_record["scancel"] = run(["scancel", child.group(1)])
    works = bool(status and status.group(1) == "0" and child)
    return {"source": source,
            "question": "Does sbatch work from inside a running job?",
            "answer": "yes" if works else "no",
            "parent_job": job["id"], "parent_stdout": text, "parent_stderr": errors,
            "child": child_record}


def write(path, doc):
    with open(path, "w") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    sys.exit(main())
