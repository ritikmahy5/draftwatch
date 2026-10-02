package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.slurm.QueueEntry;
import dev.draftwatch.exec.slurm.SlurmCli;
import dev.draftwatch.exec.slurm.SlurmJobId;
import dev.draftwatch.exec.slurm.SlurmState;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.StateLockException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code draftwatch unschedule}: ends the schedule (DECISIONS.md D63). It removes the token
 * first, so no scheduled job resubmits after this, then cancels the queued ones. A job that is
 * already running finishes its pass.
 */
public final class UnscheduleCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;
  private final SlurmCli slurm;
  private final String user;

  /** @param user whose jobs are listed by name, normally this process's user */
  public UnscheduleCommand(
      ConfigLoader loader,
      Function<DraftwatchConfig, Services> services,
      SlurmCli slurm,
      String user) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
    this.slurm = Objects.requireNonNull(slurm, "slurm");
    this.user = Objects.requireNonNull(user, "user");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    int usage = context.requireNoArguments("unschedule", args);
    if (usage != Cli.EXIT_OK) {
      return usage;
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    Schedule schedule = Schedule.of(config, List.of());
    Services s = services.apply(config);
    StateLock.Held lock;
    try {
      lock = s.stateLock().acquire(config.stateDir(), "unschedule");
    } catch (StateLockException e) {
      return context.fail(e.getMessage());
    }
    try {
      return unschedule(context, schedule);
    } catch (IOException e) {
      return context.fail("cannot update " + schedule.dir() + ": " + e.getMessage());
    } finally {
      lock.close();
    }
  }

  private int unschedule(CommandContext context, Schedule schedule) throws IOException {
    PrintStream out = context.out();
    if (schedule.token().isEmpty() && schedule.jobId().isEmpty()) {
      out.println("nothing is scheduled for " + schedule.dir().getParent());
      return Cli.EXIT_OK;
    }
    Files.deleteIfExists(schedule.active());
    out.println("removed " + schedule.active() + ": no scheduled job resubmits after this");
    try {
      for (QueueEntry e : slurm.queueByName(schedule.jobName(), user)) {
        SlurmState.Group group =
            e.state().map(SlurmState::group).orElse(SlurmState.Group.UNMAPPED);
        if (group == SlurmState.Group.WAITING) {
          slurm.cancel(SlurmJobId.parse(e.jobId()));
          out.println("cancelled queued Slurm job " + e.jobId());
        } else if (e.isAlive()) {
          out.println(
              "Slurm job " + e.jobId() + " is " + e.stateText() + "; it finishes its pass and"
                  + " does not resubmit");
        }
      }
    } catch (ExecutorException e) {
      return context.fail(
          e.getMessage() + "; no job resubmits, but cancel any queued job named "
              + schedule.jobName() + " with scancel");
    }
    Files.deleteIfExists(schedule.jobIdFile());
    Files.deleteIfExists(schedule.stopped());
    out.println("unscheduled");
    return Cli.EXIT_OK;
  }
}
