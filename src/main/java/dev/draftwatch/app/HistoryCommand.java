package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.harness.PromptSetException;
import dev.draftwatch.store.StoreException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code draftwatch history <target> --probe <id>}: the target's results under the probe's current
 * probe hash, in step order. Numbers are printed exactly as stored, each with its result file.
 */
public final class HistoryCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public HistoryCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    String target = null;
    String probeId = null;
    List<String> rest = new ArrayList<>(args);
    for (int i = 0; i < rest.size(); i++) {
      String arg = rest.get(i);
      if (arg.equals("--probe") && i + 1 < rest.size() && probeId == null) {
        probeId = rest.get(++i);
      } else if (!arg.startsWith("--") && target == null) {
        target = arg;
      } else {
        return usage(context);
      }
    }
    if (target == null || probeId == null) {
      return usage(context);
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    if (config.target(target).isEmpty()) {
      return context.fail("unknown target '" + target + "'");
    }
    Optional<Probe> probe = config.probe(probeId);
    if (probe.isEmpty()) {
      return context.fail("unknown probe '" + probeId + "'");
    }
    Services s = services.apply(config);
    ResolvedProbe resolved;
    List<Measurement> history;
    try {
      resolved = s.probeResolver().resolve(probe.get());
      history = s.results().history(target, resolved.hash());
    } catch (FingerprintException | PromptSetException | StoreException e) {
      return context.fail(e.getMessage());
    }
    PrintStream out = context.out();
    out.println(
        "target " + target + ", probe " + probeId + " (probe hash " + resolved.hash() + "): "
            + history.size() + " result(s)");
    for (Measurement m : history) {
      AggregateMetrics a = m.report().aggregate();
      out.println(
          "  step " + m.provenance().checkpoint().step() + "  alpha_mean " + a.alphaMean()
              + "  tau_mean " + a.tauMean() + "  job " + m.jobId() + "  "
              + s.results().locate(m));
    }
    return Cli.EXIT_OK;
  }

  private static int usage(CommandContext context) {
    context.err().println("draftwatch history: expected <target> --probe <id>; see --help");
    return Cli.EXIT_USAGE;
  }
}
