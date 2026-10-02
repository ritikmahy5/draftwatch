package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.discovery.CheckpointRejectedException;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.harness.PromptSetException;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.StateLockException;
import dev.draftwatch.store.StoreException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code draftwatch baseline <target> [<checkpoint>]}: shows the target's baseline and, per probe,
 * whether the baseline checkpoint has a result; with a checkpoint, sets the baseline to it first
 * (under the state lock, after the same inspection as {@code submit}). Changing the baseline does
 * not re-run detections already recorded (DECISIONS.md D51).
 */
public final class BaselineCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public BaselineCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    if (args.isEmpty() || args.size() > 2) {
      context.err().println("draftwatch baseline: expected <target> [<checkpoint>]; see --help");
      return Cli.EXIT_USAGE;
    }
    Optional<Path> checkpointDir = Optional.empty();
    if (args.size() == 2) {
      try {
        checkpointDir = Optional.of(Paths.get(args.get(1)));
      } catch (InvalidPathException e) {
        context.err().println("draftwatch baseline: invalid checkpoint path: " + e.getMessage());
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
    Optional<TargetConfig> target = config.target(args.get(0));
    if (target.isEmpty()) {
      return context.fail("unknown target '" + args.get(0) + "'");
    }
    Services s = services.apply(config);
    try {
      if (checkpointDir.isPresent()) {
        set(s, target.get(), checkpointDir.get());
      }
      show(context.out(), s, target.get());
      return Cli.EXIT_OK;
    } catch (StateLockException
        | CheckpointRejectedException
        | FingerprintException
        | PromptSetException
        | StoreException e) {
      return context.fail(e.getMessage());
    }
  }

  private static void set(Services s, TargetConfig target, Path dir) {
    StateLock.Held lock = s.stateLock().acquire(s.config().stateDir(), "baseline");
    try {
      Checkpoint checkpoint =
          s.inspector().inspect(target.target(), dir, s.completionPolicy(target.completion()));
      s.baselines().set(Baseline.of(checkpoint, s.clock().instant(), Baseline.Source.MANUAL));
    } finally {
      lock.close();
    }
  }

  private static void show(PrintStream out, Services s, TargetConfig target) {
    Optional<Baseline> baseline = s.baselines().get(target.name());
    if (baseline.isEmpty()) {
      out.println(
          "target " + target.name() + ": no baseline yet; the first measured checkpoint becomes"
              + " the baseline, or set one with 'draftwatch baseline " + target.name()
              + " <checkpoint>'");
      return;
    }
    Baseline b = baseline.get();
    out.println(
        "target " + target.name() + ": baseline step " + b.step() + ", " + b.path()
            + " (fingerprint " + b.fingerprint() + "), set " + b.setAt() + ", "
            + (b.source() == Baseline.Source.AUTO
                ? "automatically (first measured checkpoint)"
                : "manually"));
    for (String probeId : target.probeIds()) {
      Probe probe = s.config().probe(probeId).orElseThrow();
      ResolvedProbe resolved = s.probeResolver().resolve(probe);
      Optional<Measurement> latest = s.results().latest(b.fingerprint(), resolved.hash());
      if (latest.isPresent()) {
        AggregateMetrics a = latest.get().report().aggregate();
        out.println(
            "  probe " + probeId + ": measured by job " + latest.get().jobId() + ", alpha_mean "
                + a.alphaMean() + ", tau_mean " + a.tauMean() + " -> "
                + s.results().locate(latest.get()));
      } else {
        out.println(
            "  probe " + probeId + ": no result for the baseline yet; detection of new results"
                + " is deferred until it has one (submit it with 'draftwatch submit "
                + target.name() + " " + b.path() + "')");
      }
    }
  }
}
