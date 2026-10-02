package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.store.LockHolder;
import dev.draftwatch.store.StateLockException;
import dev.draftwatch.store.StoreException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code draftwatch status}: the state lock's holder, active jobs, and jobs that failed in the
 * last seven days, as of their last recorded state (DECISIONS.md D39). It only reads state, so it
 * does not take the lock.
 */
public final class StatusCommand implements CliCommand {
  static final Duration RECENT = Duration.ofDays(7);

  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public StatusCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    int usage = context.requireNoArguments("status", args);
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
    Services s = services.apply(config);
    PrintStream out = context.out();
    out.println("state_dir: " + config.stateDir());
    try {
      Optional<LockHolder> holder = s.stateLock().holder(config.stateDir());
      out.println("lock: " + holder.map(h -> "held by " + h).orElse("free"));
    } catch (StateLockException e) {
      out.println("lock: " + e.getMessage());
    }
    List<Job> jobs;
    try {
      jobs = s.jobs().all();
    } catch (StoreException e) {
      return context.fail(e.getMessage());
    }
    Instant since = s.clock().instant().minus(RECENT);
    List<Job> active = jobs.stream().filter(j -> j.state().isActive()).collect(Collectors.toList());
    List<Job> failed =
        jobs.stream()
            .filter(j -> j.state() == JobState.FAILED && !j.lastChange().at().isBefore(since))
            .collect(Collectors.toList());
    out.println();
    out.println("active jobs: " + active.size());
    active.forEach(j -> out.println("  " + line(j)));
    out.println("failed in the last 7 days: " + failed.size());
    failed.forEach(
        j ->
            out.println(
                "  " + line(j) + ", " + j.failureReason().map(r -> r.wireName()).orElse("?")
                    + ": " + j.lastChange().cause()));
    return Cli.EXIT_OK;
  }

  private static String line(Job job) {
    return job.id() + "  " + job.state() + "  attempt " + job.attempt() + "  target "
        + job.spec().checkpoint().targetName() + " step " + job.spec().checkpoint().step()
        + "  probe " + job.spec().probe().probe().id() + "  since " + job.lastChange().at();
  }
}
