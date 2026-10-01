package dev.draftwatch.app;

import dev.draftwatch.config.CanonicalJson;
import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DetectorSpec;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.ExecutorConfig;
import dev.draftwatch.config.ExecutorType;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.config.ProbeResolver;
import dev.draftwatch.config.SlurmConfig;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.config.TriggerSpec;
import dev.draftwatch.discovery.StepExtractor;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.domain.Target;
import dev.draftwatch.domain.WireNamed;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.fingerprint.FingerprintMethod;
import dev.draftwatch.harness.PromptSetException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * {@code draftwatch validate}: validates the config, resolves every probe (fingerprints its
 * draft, reads its prompt file, computes its probe hash), and prints the resolved probes and
 * trigger chains. Checkpoint directories may not exist yet; that is reported, not an error
 * (DECISIONS.md D28).
 */
public final class ValidateCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<FingerprintMethod, ProbeResolver> resolvers;

  /**
   * Creates the command.
   *
   * @param resolvers gives the probe resolver for the config's fingerprint method
   */
  public ValidateCommand(
      ConfigLoader loader, Function<FingerprintMethod, ProbeResolver> resolvers) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    int usage = context.requireNoArguments("validate", args);
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
    ProbeResolver resolver = resolvers.apply(config.fingerprintMethod());
    List<ResolvedProbe> resolved = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < config.probes().size(); i++) {
      Probe probe = config.probes().get(i);
      try {
        resolved.add(resolver.resolve(probe));
      } catch (FingerprintException | PromptSetException e) {
        problems.add("probes[" + i + "] (" + probe.id() + "): " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      context
          .err()
          .println(
              config.configFile() + ": " + count(problems.size(), "probe") + " cannot be resolved");
      for (String problem : problems) {
        context.err().println("  " + problem);
      }
      return Cli.EXIT_FAILURE;
    }
    print(context.out(), config, resolved);
    return Cli.EXIT_OK;
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static void print(PrintStream out, DraftwatchConfig config, List<ResolvedProbe> probes) {
    out.println("config:      " + config.configFile());
    out.println("state_dir:   " + config.stateDir());
    out.println("executor:    " + describe(config.executor()));
    out.println("harness:     " + String.join(" ", config.harness().command()));
    out.println("fingerprint: " + config.fingerprintMethod().wireName());
    for (ResolvedProbe resolved : probes) {
      Probe p = resolved.probe();
      out.println();
      out.println("probe " + p.id());
      out.println("  probe hash:        " + resolved.hash());
      out.println(
          "  draft:             "
              + p.draft().id()
              + " ("
              + p.draft().structure().wireName()
              + ") "
              + p.draft().path());
      out.println("  draft fingerprint: " + resolved.draftFingerprint());
      out.println(
          "  prompts:           "
              + p.promptsPath()
              + " ("
              + count(resolved.promptSet().promptCount(), "prompt")
              + ", sha256 "
              + resolved.promptSet().sha256()
              + ")");
      out.println(
          "  decoding:          " + CanonicalJson.write(ProbeHasher.decodingJson(p.decoding())));
      out.println("  estimator:         " + p.estimator().wireName());
      out.println("  seeds:             " + p.seeds());
    }
    for (TargetConfig t : config.targets()) {
      Target target = t.target();
      out.println();
      out.println("target " + target.name() + " (" + target.checkpointType().wireName() + ")");
      for (Path dir : target.checkpointDirs()) {
        out.println(
            "  checkpoint_dir:    "
                + dir
                + (Files.isDirectory(dir) ? "" : " (does not exist yet)"));
      }
      target.baseModel().ifPresent(base -> out.println("  base_model:        " + base));
      out.println("  completion:        " + t.completion().describe());
      out.println(
          "  step:              "
              + StepExtractor.TRAINER_STATE_FILE
              + " global_step, else step_regex "
              + target.stepRegex().pattern());
      out.println("  final_marker:      " + target.finalMarker());
      out.println("  probes:            " + String.join(", ", t.probeIds()));
      StringJoiner chain = new StringJoiner(" -> ");
      for (TriggerSpec trigger : t.triggers()) {
        chain.add(trigger.describe());
      }
      out.println("  triggers:          " + chain);
      for (DetectorSpec detector : t.detectors()) {
        out.println("  detector:          " + detector.describe());
      }
      out.println("  on_regression:     " + names(t.onRegression()));
    }
    out.println();
    out.println(
        "OK: "
            + count(probes.size(), "probe")
            + ", "
            + count(config.targets().size(), "target"));
  }

  private static String describe(ExecutorConfig executor) {
    StringBuilder text = new StringBuilder(executor.type().wireName());
    text.append(" (max_retries ").append(executor.maxRetries());
    if (executor.type() == ExecutorType.SLURM && executor.slurm().isPresent()) {
      SlurmConfig slurm = executor.slurm().get();
      slurm.partition().ifPresent(v -> text.append(", partition ").append(v));
      slurm.gres().ifPresent(v -> text.append(", gres ").append(v));
      slurm.time().ifPresent(v -> text.append(", time ").append(v));
      text.append(", requeue_on_preempt ").append(slurm.requeueOnPreempt());
      if (!slurm.extraSbatchArgs().isEmpty()) {
        text.append(", extra_sbatch_args ").append(slurm.extraSbatchArgs());
      }
    }
    return text.append(")").toString();
  }

  private static String names(List<? extends WireNamed> values) {
    if (values.isEmpty()) {
      return "(none)";
    }
    StringJoiner joined = new StringJoiner(", ");
    for (WireNamed value : values) {
      joined.add(value.wireName());
    }
    return joined.toString();
  }
}
