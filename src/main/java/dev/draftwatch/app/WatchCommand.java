package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.ExecutorType;
import dev.draftwatch.discovery.Discovery;
import dev.draftwatch.exec.Job;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.StateLockException;
import dev.draftwatch.store.StoreException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code draftwatch watch [--once] [--interval <duration>]}: runs watch passes (DECISIONS.md D55).
 * Each pass holds the state lock and releases it before waiting, so other commands can run in
 * between. Looping is refused with the slurm executor: login nodes are not for long-running
 * processes (SPEC.md F3, DECISIONS.md D9).
 */
public final class WatchCommand implements CliCommand {
  static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

  private static final Map<Discovery.SkipKind, String> LABELS =
      Map.of(
          Discovery.SkipKind.MISSING_DIRECTORY, "missing",
          Discovery.SkipKind.INCOMPLETE, "waiting",
          Discovery.SkipKind.REJECTED, "rejected");

  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public WatchCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    boolean once = false;
    Duration interval = DEFAULT_INTERVAL;
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("--once")) {
        once = true;
      } else if (arg.equals("--interval") && i + 1 < args.size()) {
        try {
          interval = Durations.parse(args.get(++i));
        } catch (IllegalArgumentException e) {
          context.err().println("draftwatch watch: --interval " + e.getMessage());
          return Cli.EXIT_USAGE;
        }
      } else {
        context.err().println("draftwatch watch: unexpected argument '" + arg + "'; see --help");
        return Cli.EXIT_USAGE;
      }
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    if (!once && config.executor().type() == ExecutorType.SLURM) {
      return context.fail(
          "looping watch is refused with executor.type slurm: cluster login nodes are not for"
              + " long-running processes; run 'draftwatch watch --once', or schedule it with"
              + " 'draftwatch schedule'");
    }
    Services s = services.apply(config);
    WatchService watch;
    try {
      watch = s.watch();
    } catch (UnsupportedOperationException e) {
      return context.fail(e.getMessage());
    }
    while (true) {
      int result = onePass(context, s, watch, once);
      if (once) {
        return result;
      }
      try {
        s.sleeper().sleep(interval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        context.out().println("watch stopped");
        return Cli.EXIT_OK;
      }
    }
  }

  private static int onePass(
      CommandContext context, Services s, WatchService watch, boolean once) {
    StateLock.Held lock;
    try {
      lock = s.stateLock().acquire(s.config().stateDir(), "watch");
    } catch (StateLockException e) {
      if (once) {
        return context.fail(e.getMessage());
      }
      context.err().println("draftwatch: skipping this pass: " + e.getMessage());
      return Cli.EXIT_OK;
    }
    try {
      context.out().println("watch pass at " + s.clock().instant());
      print(context.out(), watch.pass(), s);
      return Cli.EXIT_OK;
    } catch (StoreException e) {
      return context.fail(e.getMessage());
    } finally {
      lock.close();
    }
  }

  /** The pass's details, then a closing {@code done:} line with its counts. */
  static void print(PrintStream out, PassReport r, Services s) {
    for (String error : r.errors()) {
      out.println("  error: " + error);
    }
    for (Job job : r.baselineSubmitted()) {
      out.println("  submitted job " + job.id() + ": " + where(job) + " (baseline measurement)");
    }
    for (PassReport.Submitted sub : r.submitted()) {
      out.println(
          "  submitted job " + sub.job().id() + ": " + where(sub.job()) + " (" + sub.why() + ")");
    }
    for (Discovery.Skipped skipped : r.skipped()) {
      out.println("  " + LABELS.get(skipped.kind()) + ": " + skipped.reason());
    }
    if (!r.notMeasuredByRule().isEmpty()) {
      StringBuilder text = new StringBuilder("  not measured, by deciding rule:");
      for (Map.Entry<String, Integer> e : r.notMeasuredByRule().entrySet()) {
        text.append(' ').append(e.getKey()).append(' ').append(e.getValue());
      }
      out.println(text);
    }
    MeasurementRunner runner = s.runner();
    for (Job job : r.finished()) {
      out.println("  " + SubmitCommand.outcome(runner, job));
    }
    out.println(
        "  done: " + (r.submitted().size() + r.baselineSubmitted().size()) + " submitted, "
            + r.finished().size() + " finished, " + r.stillActive() + " still running");
  }

  private static String where(Job job) {
    return "target " + job.spec().checkpoint().targetName() + " step "
        + job.spec().checkpoint().step() + " probe " + job.spec().probe().probe().id();
  }
}
