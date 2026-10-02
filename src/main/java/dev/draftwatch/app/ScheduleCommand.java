package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.ExecutorType;
import dev.draftwatch.config.SbatchOptions;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.slurm.QueueEntry;
import dev.draftwatch.exec.slurm.SlurmCli;
import dev.draftwatch.exec.slurm.SlurmJobId;
import dev.draftwatch.store.AtomicFiles;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.StateLockException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

/**
 * {@code draftwatch schedule [--interval 15m]}: submits the CPU-only, self-resubmitting job that
 * runs {@code watch --once} on the cluster (SPEC.md F3; DECISIONS.md D9, D63). Refused unless
 * {@code executor.type} is slurm, and while an earlier schedule's job is still queued or running.
 */
public final class ScheduleCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;
  private final SlurmCli slurm;
  private final List<String> launcher;
  private final Random random;

  /**
   * @param launcher the command that starts draftwatch inside the job
   * @param random source of the schedule's token
   */
  public ScheduleCommand(
      ConfigLoader loader,
      Function<DraftwatchConfig, Services> services,
      SlurmCli slurm,
      List<String> launcher,
      Random random) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
    this.slurm = Objects.requireNonNull(slurm, "slurm");
    this.launcher = List.copyOf(launcher);
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    Duration interval = Schedule.DEFAULT_INTERVAL;
    if (args.size() == 2 && args.get(0).equals("--interval")) {
      try {
        interval = Durations.parse(args.get(1));
      } catch (IllegalArgumentException e) {
        context.err().println("draftwatch schedule: --interval " + e.getMessage());
        return Cli.EXIT_USAGE;
      }
      if (interval.compareTo(Schedule.MIN_INTERVAL) < 0) {
        context.err().println(
            "draftwatch schedule: --interval must be at least 60s, was " + args.get(1));
        return Cli.EXIT_USAGE;
      }
    } else if (!args.isEmpty()) {
      context.err().println("draftwatch schedule: expected [--interval 15m]; see --help");
      return Cli.EXIT_USAGE;
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    if (config.executor().type() != ExecutorType.SLURM) {
      return context.fail(
          "schedule needs executor.type slurm; with executor.type "
              + config.executor().type().wireName() + ", run 'draftwatch watch' instead");
    }
    Schedule schedule = Schedule.of(config, launcher);
    if (schedule.dir().toString().contains("%")) {
      return context.fail(
          schedule.dir() + " contains '%', which sbatch expands in file names; choose a"
              + " state_dir without it");
    }
    Services s = services.apply(config);
    StateLock.Held lock;
    try {
      lock = s.stateLock().acquire(config.stateDir(), "schedule");
    } catch (StateLockException e) {
      return context.fail(e.getMessage());
    }
    try {
      return schedule(context, s, schedule, interval);
    } catch (IOException e) {
      return context.fail("cannot write " + schedule.dir() + ": " + e.getMessage());
    } catch (ExecutorException e) {
      return context.fail(e.getMessage());
    } finally {
      lock.close();
    }
  }

  private int schedule(CommandContext context, Services s, Schedule schedule, Duration interval)
      throws IOException {
    PrintStream out = context.out();
    Optional<String> previous = schedule.jobId();
    if (previous.isPresent() && schedule.token().isPresent()) {
      Optional<QueueEntry> entry = slurm.queue(SlurmJobId.parse(previous.get()));
      if (entry.isPresent() && entry.get().isAlive()) {
        return context.fail(
            "already scheduled: Slurm job " + previous.get() + " is " + entry.get().stateText()
                + "; run 'draftwatch unschedule' first");
      }
      out.println("replacing the schedule whose last job, " + previous.get() + ", has ended");
    }
    Files.createDirectories(schedule.dir());
    String token = String.format("%016x%016x", random.nextLong(), random.nextLong());
    AtomicFiles.write(
        schedule.script(),
        schedule.script(token, interval, s.clock().instant()).getBytes(StandardCharsets.UTF_8));
    AtomicFiles.write(schedule.active(), (token + "\n").getBytes(StandardCharsets.UTF_8));
    SlurmJobId id;
    try {
      id =
          slurm.submit(
              schedule.options(), schedule.script(), List.of(), SbatchOptions.GPU_VARIABLES);
    } catch (ExecutorException e) {
      Files.deleteIfExists(schedule.active());
      throw e;
    }
    AtomicFiles.write(schedule.jobIdFile(), (id + "\n").getBytes(StandardCharsets.UTF_8));
    Files.deleteIfExists(schedule.stopped());
    out.println(
        "scheduled: Slurm job " + id + " runs 'watch --once' as soon as it starts, then every "
            + interval.getSeconds() + " s (job name " + schedule.jobName() + ")");
    out.println("  script: " + schedule.script());
    out.println("  log:    " + schedule.log());
    out.println("stop it with 'draftwatch unschedule'");
    return Cli.EXIT_OK;
  }
}
