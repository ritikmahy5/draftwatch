package dev.draftwatch.exec.slurm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.config.SlurmConfig;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.ExecutorStatus;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobSpec;
import dev.draftwatch.exec.LocalExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs attempts as Slurm batch jobs (ARCHITECTURE.md, "Slurm state mapping"; DECISIONS.md
 * D58–D60).
 *
 * <p>Each attempt's run directory receives {@code job.sbatch}, a {@code /bin/sh} script that runs
 * the harness command given as its arguments and records its exit status in {@code exit_code},
 * and {@code sbatch.json}, the exact sbatch argv. {@link #status} asks squeue first. A job squeue
 * no longer lists, or lists in a terminal state, is resolved from sacct, which alone reports exit
 * codes. The only state kept between polls is {@code slurm_unresolved.json} in the run
 * directory, so every draftwatch process sees the same history.
 */
public final class SlurmExecutor implements Executor {
  public static final String NAME = "slurm";
  public static final String SCRIPT_FILE = "job.sbatch";
  public static final String SBATCH_FILE = "sbatch.json";
  public static final String UNRESOLVED_FILE = "slurm_unresolved.json";

  /** How long an observation may stay unresolved before the attempt fails (D60). */
  public static final Duration UNRESOLVED_GRACE = Duration.ofMinutes(5);

  private final SlurmConfig config;
  private final SlurmCli cli;
  private final Clock clock;
  private final Consumer<String> warnings;
  private final ObjectMapper json = new ObjectMapper();

  /** @param warnings receives one line per unresolved observation (D60) */
  public SlurmExecutor(
      SlurmConfig config, SlurmCli cli, Clock clock, Consumer<String> warnings) {
    this.config = Objects.requireNonNull(config, "config");
    this.cli = Objects.requireNonNull(cli, "cli");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.warnings = Objects.requireNonNull(warnings, "warnings");
  }

  @Override
  public String name() {
    return NAME;
  }

  // --- submit ------------------------------------------------------------------------------

  @Override
  public JobHandle submit(JobSpec spec) {
    Path runDir = spec.runDir();
    if (runDir.toString().contains("%")) {
      throw new ExecutorException(
          "run directory " + runDir + " contains '%', which sbatch expands in file names;"
              + " choose a state_dir without it");
    }
    try {
      Files.createDirectories(runDir);
    } catch (IOException e) {
      throw new ExecutorException("cannot create run directory " + runDir + ": " + e, e);
    }
    if (Files.exists(runDir.resolve(LocalExecutor.EXIT_FILE))) {
      throw new ExecutorException(runDir + " already holds a finished attempt");
    }
    Path script = runDir.resolve(SCRIPT_FILE);
    List<String> options = options(spec);
    write(script, script(spec));
    write(
        runDir.resolve(SBATCH_FILE),
        argvJson(SlurmCli.sbatchArgv(options, script, spec.command())));
    SlurmJobId id = cli.submit(options, script, spec.command(), Set.of());
    return JobHandle.of(NAME, id.toString(), runDir, clock.instant(), Optional.empty());
  }

  /** The sbatch options of one attempt, in a fixed order (D58, D65). */
  List<String> options(JobSpec spec) {
    Path runDir = spec.runDir();
    List<String> options = new ArrayList<>();
    options.add("--job-name=draftwatch-" + spec.jobId() + "-a" + spec.attempt());
    options.add("--output=" + runDir.resolve(LocalExecutor.STDOUT_FILE));
    options.add("--error=" + runDir.resolve(LocalExecutor.STDERR_FILE));
    options.add("--open-mode=append"); // a requeued run adds to the logs of the preempted one
    options.add("--chdir=" + spec.workingDir());
    config.partition().ifPresent(p -> options.add("--partition=" + p));
    config.gres().ifPresent(g -> options.add("--gres=" + g));
    config.time().ifPresent(t -> options.add("--time=" + t));
    options.add(config.requeueOnPreempt() ? "--requeue" : "--no-requeue");
    options.addAll(config.extraSbatchArgs());
    return options;
  }

  /** The batch script: removes a stale exit code, runs "$@", and records its status. */
  static String script(JobSpec spec) {
    String exit = ShellQuote.quote(spec.runDir().resolve(LocalExecutor.EXIT_FILE).toString());
    String tmp =
        ShellQuote.quote(spec.runDir().resolve(LocalExecutor.EXIT_FILE + ".tmp").toString());
    return "#!/bin/sh\n"
        + "# draftwatch job " + spec.jobId() + ", attempt " + spec.attempt()
        + ": runs the harness command given as\n"
        + "# arguments and records its exit status (DECISIONS.md D59). A requeued run starts"
        + " over.\n"
        + "rm -f " + exit + " " + tmp + "\n"
        + "\"$@\"\n"
        + "code=$?\n"
        + "printf '%s\\n' \"$code\" > " + tmp + " && mv " + tmp + " " + exit + "\n"
        + "exit \"$code\"\n";
  }

  // --- status ------------------------------------------------------------------------------

  /**
   * The attempt's status from squeue, then sacct.
   *
   * @throws ExecutorException if squeue, sacct, or scancel fails; nothing is recorded then
   */
  @Override
  public ExecutorStatus status(JobHandle handle) {
    SlurmJobId id = SlurmJobId.parse(handle.nativeId());
    Optional<QueueEntry> queued = cli.queue(id);
    if (queued.isPresent()) {
      QueueEntry entry = queued.get();
      Optional<ExecutorStatus> fromQueue = fromQueue(handle, id, entry);
      if (fromQueue.isPresent()) {
        return fromQueue.get();
      }
    }
    Optional<AccountingRecord> record = cli.accounting(id);
    if (record.isEmpty()) {
      return unresolved(
          handle,
          id,
          queued.isPresent()
              ? "squeue reports " + queued.get().stateText() + " but sacct has no record yet"
              : "in neither squeue nor sacct",
          Optional.empty());
    }
    AccountingRecord r = record.get();
    if (r.state().isEmpty() || r.state().get().group() == SlurmState.Group.UNMAPPED) {
      return unresolved(handle, id, "sacct reports unknown state " + r.stateText(), queued);
    }
    if (r.state().get().group() != SlurmState.Group.TERMINAL) {
      return unresolved(
          handle,
          id,
          queued.map(q -> "squeue reports " + q.stateText()).orElse("squeue no longer lists it")
              + ", but sacct still reports " + r.stateText(),
          Optional.empty());
    }
    return terminal(handle, id, r);
  }

  /** What squeue alone decides; empty when sacct must decide (a terminal state). */
  private Optional<ExecutorStatus> fromQueue(JobHandle handle, SlurmJobId id, QueueEntry e) {
    if (e.state().isEmpty() || e.state().get().group() == SlurmState.Group.UNMAPPED) {
      return Optional.of(
          unresolved(handle, id, "squeue reports unknown state " + e.stateText(), Optional.of(e)));
    }
    SlurmState state = e.state().get();
    switch (state.group()) {
      case WAITING:
        resolved(handle);
        return Optional.of(ExecutorStatus.queued());
      case ACTIVE:
        resolved(handle);
        return Optional.of(ExecutorStatus.running(e.start()));
      case REQUEUING:
        resolved(handle);
        if (config.requeueOnPreempt()) {
          return Optional.of(ExecutorStatus.queued());
        }
        cli.cancel(id);
        return Optional.of(
            ExecutorStatus.failed(
                FailureReason.PREEMPTED_NO_REQUEUE,
                Optional.empty(),
                Optional.empty(),
                "Slurm job " + id + " is " + e.stateText()
                    + " although requeue_on_preempt is false; cancelled it"));
      case TERMINAL:
        if (state == SlurmState.PREEMPTED) {
          resolved(handle);
          return Optional.of(
              config.requeueOnPreempt()
                  ? ExecutorStatus.queued()
                  : ExecutorStatus.failed(
                      FailureReason.PREEMPTED_NO_REQUEUE,
                      Optional.empty(),
                      Optional.empty(),
                      "Slurm job " + id + " was PREEMPTED and requeue_on_preempt is false"));
        }
        return Optional.empty();
      default:
        throw new IllegalStateException("unhandled Slurm state group " + state.group());
    }
  }

  /** The outcome of a job sacct reports as finished (D59). */
  private ExecutorStatus terminal(JobHandle handle, SlurmJobId id, AccountingRecord r) {
    Optional<Instant> start = r.start();
    Optional<Instant> end = r.end();
    String what = "Slurm job " + id + " " + r.describe();
    switch (r.state().get()) {
      case COMPLETED:
        if (r.exitCode().orElse(0) != 0 || r.signal().orElse(0) != 0) {
          resolved(handle);
          return ExecutorStatus.failed(FailureReason.UNEXPECTED_EXIT, start, end, what);
        }
        return exited(handle, id, 0, r);
      case FAILED:
        if (r.signal().orElse(0) != 0 || r.exitCode().orElse(0) == 0) {
          resolved(handle);
          return ExecutorStatus.failed(FailureReason.UNEXPECTED_EXIT, start, end, what);
        }
        return exited(handle, id, r.exitCode().getAsInt(), r);
      case OUT_OF_MEMORY:
        resolved(handle);
        return ExecutorStatus.failed(FailureReason.OUT_OF_MEMORY, start, end, what);
      case TIMEOUT:
      case DEADLINE:
        resolved(handle);
        return ExecutorStatus.failed(FailureReason.TIMEOUT, start, end, what);
      case NODE_FAIL:
      case BOOT_FAIL:
        resolved(handle);
        return ExecutorStatus.failed(FailureReason.NODE_FAILURE, start, end, what);
      case PREEMPTED:
        resolved(handle);
        return ExecutorStatus.failed(
            FailureReason.PREEMPTED_NO_REQUEUE, start, end, what + " and was not requeued");
      case CANCELLED:
        resolved(handle);
        return ExecutorStatus.cancelled(start, end, what);
      default:
        throw new IllegalStateException("unhandled terminal Slurm state " + r.state().get());
    }
  }

  /** EXITED with sacct's exit code, once the batch script's {@code exit_code} agrees (D59). */
  private ExecutorStatus exited(JobHandle handle, SlurmJobId id, int code, AccountingRecord r) {
    Path file = handle.runDir().resolve(LocalExecutor.EXIT_FILE);
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8).trim();
    } catch (NoSuchFileException e) {
      return unresolved(
          handle, id, "sacct reports " + r.describe() + " but " + file + " is not visible yet",
          Optional.empty());
    } catch (IOException e) {
      throw new ExecutorException("cannot read " + file + ": " + e, e);
    }
    resolved(handle);
    if (!text.equals(Integer.toString(code))) {
      return ExecutorStatus.failed(
          FailureReason.UNEXPECTED_EXIT,
          r.start(),
          r.end(),
          file + " says '" + text + "' but sacct reports " + r.describe() + " for Slurm job "
              + id);
    }
    return ExecutorStatus.exited(code, r.start(), r.end());
  }

  // --- unresolved observations (D60) -------------------------------------------------------

  /**
   * Records one unresolved observation. Within the grace period nothing changes; after it, the
   * attempt fails, and a job squeue still lists alive is cancelled first so a retry never runs
   * beside it.
   */
  private ExecutorStatus unresolved(
      JobHandle handle, SlurmJobId id, String detail, Optional<QueueEntry> listed) {
    Path file = handle.runDir().resolve(UNRESOLVED_FILE);
    Instant now = clock.instant();
    Optional<JsonNode> earlier = readUnresolved(file);
    Instant first = earlier.map(n -> Instant.parse(n.get("first_seen").textValue())).orElse(now);
    int polls = earlier.map(n -> n.get("observations").intValue()).orElse(0) + 1;
    warnings.accept(
        "Slurm job " + id + " (" + handle.runDir() + "): " + detail + "; unresolved since "
            + first + ", poll " + polls);
    if (earlier.isPresent() && !now.isBefore(first.plus(UNRESOLVED_GRACE))) {
      String cancelled = "";
      if (listed.isPresent() && listed.get().isAlive()) {
        cli.cancel(id);
        cancelled = "; cancelled it";
      }
      writeUnresolved(file, first, polls, detail);
      return ExecutorStatus.failed(
          FailureReason.UNEXPECTED_EXIT,
          Optional.empty(),
          Optional.empty(),
          "Slurm job " + id + ": " + detail + " for " + Duration.between(first, now).getSeconds()
              + " s (" + polls + " polls)" + cancelled);
    }
    writeUnresolved(file, first, polls, detail);
    return ExecutorStatus.unresolved(detail);
  }

  private void resolved(JobHandle handle) {
    Path file = handle.runDir().resolve(UNRESOLVED_FILE);
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new ExecutorException("cannot remove " + file + ": " + e, e);
    }
  }

  private Optional<JsonNode> readUnresolved(Path file) {
    try {
      JsonNode node = json.readTree(Files.readAllBytes(file));
      if (node == null
          || !node.path("first_seen").isTextual()
          || !node.path("observations").isInt()) {
        throw new ExecutorException(file + " is malformed: " + node);
      }
      return Optional.of(node);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new ExecutorException("cannot read " + file + ": " + e, e);
    }
  }

  private void writeUnresolved(Path file, Instant first, int polls, String detail) {
    ObjectNode node = json.createObjectNode();
    node.put("first_seen", first.toString());
    node.put("observations", polls);
    node.put("last", detail);
    try {
      write(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
    } catch (IOException e) {
      throw new ExecutorException("cannot write " + file + ": " + e, e);
    }
  }

  // --- cancel ------------------------------------------------------------------------------

  /** Cancels the job if squeue lists it alive; a finished or unknown job is left alone. */
  @Override
  public void cancel(JobHandle handle) {
    SlurmJobId id = SlurmJobId.parse(handle.nativeId());
    Optional<QueueEntry> entry = cli.queue(id);
    if (entry.isPresent() && entry.get().isAlive()) {
      cli.cancel(id);
    }
  }

  // --- files -------------------------------------------------------------------------------

  private String argvJson(List<String> argv) {
    ArrayNode array = json.createArrayNode();
    argv.forEach(array::add);
    try {
      return json.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n";
    } catch (IOException e) {
      throw new ExecutorException("cannot serialize the sbatch command: " + e, e);
    }
  }

  /** Writes {@code text} to a temporary file beside {@code file}, then renames it. */
  private static void write(Path file, String text) {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Files.writeString(tmp, text, StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new ExecutorException("cannot write " + file + ": " + e, e);
    }
  }
}
