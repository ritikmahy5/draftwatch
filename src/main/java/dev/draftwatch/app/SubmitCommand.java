package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.ExecutorType;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.discovery.CheckpointRejectedException;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.harness.PromptSetException;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.StateLockException;
import dev.draftwatch.store.StoreException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code draftwatch submit <target> <checkpoint>}: measures one checkpoint with every probe of
 * its target, under the state lock (DECISIONS.md D36). The checkpoint goes through the same
 * completion, step, and fingerprint logic as {@code watch}; trigger rules do not apply to an
 * explicit request. With the local executor the command waits until its jobs are done; with the
 * slurm executor it returns once sbatch has accepted them, and {@code watch --once} or the
 * schedule collects the results (DECISIONS.md D61).
 */
public final class SubmitCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public SubmitCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    if (args.size() != 2) {
      context.err().println("draftwatch submit: expected <target> <checkpoint>; see --help");
      return Cli.EXIT_USAGE;
    }
    Path checkpointDir;
    try {
      checkpointDir = Paths.get(args.get(1));
    } catch (InvalidPathException e) {
      context.err().println("draftwatch submit: invalid checkpoint path: " + e.getMessage());
      return Cli.EXIT_USAGE;
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    Optional<TargetConfig> target = config.target(args.get(0));
    if (target.isEmpty()) {
      String configured =
          config.targets().stream().map(TargetConfig::name).collect(Collectors.joining(", "));
      return context.fail("unknown target '" + args.get(0) + "'; configured: " + configured);
    }
    Services s = services.apply(config);
    MeasurementRunner runner = s.runner();
    StateLock.Held lock;
    try {
      lock = s.stateLock().acquire(config.stateDir(), "submit");
    } catch (StateLockException e) {
      return context.fail(e.getMessage());
    }
    try {
      return submit(context, s, runner, target.get(), checkpointDir);
    } catch (CheckpointRejectedException
        | FingerprintException
        | PromptSetException
        | StoreException e) {
      return context.fail(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return context.fail(
          "interrupted; started jobs keep running and their state is in " + config.stateDir());
    } finally {
      lock.close();
    }
  }

  private static int submit(
      CommandContext context,
      Services s,
      MeasurementRunner runner,
      TargetConfig target,
      Path checkpointDir)
      throws InterruptedException {
    PrintStream out = context.out();
    Checkpoint checkpoint =
        s.inspector()
            .inspect(target.target(), checkpointDir, s.completionPolicy(target.completion()));
    List<ResolvedProbe> probes = new ArrayList<>();
    for (String id : target.probeIds()) {
      Probe probe = s.config().probe(id).orElseThrow();
      probes.add(s.probeResolver().resolve(probe));
    }
    out.println(
        "checkpoint " + checkpoint.path() + ": step " + checkpoint.step() + ", "
            + checkpoint.type().wireName() + (checkpoint.isFinal() ? ", final" : "")
            + ", fingerprint " + checkpoint.fingerprint());
    List<Job> jobs = new ArrayList<>();
    for (ResolvedProbe probe : probes) {
      int earlier = s.results().find(checkpoint.fingerprint(), probe.hash()).size();
      if (earlier > 0) {
        out.println(
            "note: " + earlier + " earlier result(s) exist for this checkpoint and probe "
                + probe.probe().id() + "; measuring again");
      }
      Job job = runner.submit(runner.create(checkpoint, probe));
      out.println(
          "job " + job.id() + ": probe " + probe.probe().id() + " " + job.state()
              + job.handle().map(h -> " as " + h).orElse(""));
      jobs.add(job);
    }
    if (s.config().executor().type() == ExecutorType.SLURM) {
      return submittedToSlurm(out, runner, jobs);
    }
    boolean allSucceeded = true;
    for (Job job : runner.runToCompletion(jobs, s.sleeper())) {
      allSucceeded &= job.state() == JobState.SUCCEEDED;
      out.println(outcome(runner, job));
    }
    return allSucceeded ? Cli.EXIT_OK : Cli.EXIT_FAILURE;
  }

  /** Reports the Slurm submissions; succeeds if sbatch accepted every job (D61). */
  private static int submittedToSlurm(PrintStream out, MeasurementRunner runner, List<Job> jobs) {
    boolean allSubmitted = true;
    for (Job job : jobs) {
      if (job.state() != JobState.SUBMITTED) {
        allSubmitted = false;
        out.println(outcome(runner, job));
      }
    }
    if (allSubmitted) {
      out.println(
          "submitted to Slurm; 'draftwatch watch --once' or the schedule collects the results");
    }
    return allSubmitted ? Cli.EXIT_OK : Cli.EXIT_FAILURE;
  }

  /** One line per job; every number is followed by the result file it comes from. */
  static String outcome(MeasurementRunner runner, Job job) {
    String probe = job.spec().probe().probe().id();
    if (job.state() == JobState.SUCCEEDED) {
      Optional<Measurement> result = runner.result(job);
      if (result.isEmpty()) {
        return "job " + job.id() + " SUCCEEDED (probe " + probe + ") but its result is missing";
      }
      AggregateMetrics a = result.get().report().aggregate();
      return "job " + job.id() + " SUCCEEDED: probe " + probe + ", alpha_mean " + a.alphaMean()
          + ", tau_mean " + a.tauMean() + " (attempt " + job.attempt() + ") -> "
          + runner.locate(result.get());
    }
    return "job " + job.id() + " " + job.state() + ": probe " + probe + ", attempt "
        + job.attempt()
        + job.failureReason().map(r -> ", " + r.wireName()).orElse("")
        + ": " + job.lastChange().cause();
  }
}
