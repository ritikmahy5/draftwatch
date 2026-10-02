package dev.draftwatch.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.Target;
import dev.draftwatch.domain.WireNamed;
import dev.draftwatch.fingerprint.FingerprintMethod;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a parsed config document into a {@link DraftwatchConfig}, or throws a
 * {@link ConfigException} listing every problem with its field path.
 *
 * <p>Schema rules (DECISIONS.md D20): unknown keys are errors; a key with no value is an error;
 * defaults exist only where the docs define one. Paths are resolved against the config file's
 * directory (D21). Cross-field rules:
 *
 * <ul>
 *   <li>{@code temperature: 0} allows exactly one seed (SPEC.md F5);
 *   <li>{@code base_model} is required for, and only valid for, {@code checkpoint_type: adapter};
 *   <li>a trigger chain contains {@code not_already_measured}, and {@code always_final} comes
 *       after it (SPEC.md F2, D22);
 *   <li>{@code noise_floor} requires {@code sigma}; {@code draft.structure} must be
 *       {@code chain} (D5);
 *   <li>target probe references name declared probes; ids and names are unique.
 * </ul>
 */
public final class ConfigValidator {
  /** SPEC.md F1 forbids this file as a completion marker. */
  static final String INDEX_FILE = "model.safetensors.index.json";

  /** The six formats {@code sbatch --time} accepts (DECISIONS.md D28). */
  private static final Pattern SLURM_TIME =
      Pattern.compile("\\d+(?::\\d+){0,2}|\\d+-\\d+(?::\\d+){0,2}");

  /**
   * Validates {@code document}, read from {@code configFile}.
   *
   * @throws ConfigException listing every problem found
   */
  public DraftwatchConfig validate(JsonNode document, Path configFile) {
    Path file = configFile.toAbsolutePath().normalize();
    List<ConfigError> errors = new ArrayList<>();
    DraftwatchConfig config = new Pass(file.getParent(), errors).document(file, document);
    if (!errors.isEmpty()) {
      throw new ConfigException(file, errors);
    }
    return config;
  }

  /** State of one validation: the base directory and the errors found so far. */
  private static final class Pass {
    private final Path baseDir;
    private final List<ConfigError> errors;

    Pass(Path baseDir, List<ConfigError> errors) {
      this.baseDir = baseDir;
      this.errors = errors;
    }

    /** Runs {@code factory}; a rejection the checks above missed is reported, not thrown. */
    private <T> T build(ConfigNode at, Supplier<T> factory) {
      try {
        return factory.get();
      } catch (IllegalArgumentException | NullPointerException e) {
        at.error(String.valueOf(e.getMessage()));
        return null;
      }
    }

    DraftwatchConfig document(Path file, JsonNode document) {
      ConfigNode root = ConfigNode.root(document, errors);
      if (!root.hasValue()) {
        root.error("config file is empty");
        return null;
      }
      if (!root.mapping("state_dir", "fingerprint", "executor", "harness", "probes", "targets")) {
        return null;
      }
      ConfigNode stateNode = root.optional("state_dir");
      Path stateDir =
          stateNode.isAbsent()
              ? baseDir.resolve(DraftwatchConfig.DEFAULT_STATE_DIR)
              : stateNode.asPath(baseDir);
      ConfigNode fpNode = root.optional("fingerprint");
      FingerprintMethod fingerprint =
          fpNode.isAbsent() ? FingerprintMethod.SAMPLED : fpNode.asEnum(FingerprintMethod.class);
      ExecutorConfig executor = executor(root.required("executor"));
      HarnessConfig harness = harness(root.required("harness"));
      Set<String> declaredProbeIds = new LinkedHashSet<>();
      List<Probe> probes = probes(root.required("probes"), declaredProbeIds);
      List<TargetConfig> targets = targets(root.required("targets"), declaredProbeIds);
      if (!errors.isEmpty()) {
        return null;
      }
      return build(
          root,
          () ->
              DraftwatchConfig.of(
                  file, stateDir, fingerprint, executor, harness, probes, targets));
    }

    // --- executor and harness ---------------------------------------------------------------

    private ExecutorConfig executor(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("type", "max_retries", "slurm")) {
        return null;
      }
      ExecutorType type = n.required("type").asEnum(ExecutorType.class);
      Integer maxRetries = n.required("max_retries").asInt(0);
      ConfigNode slurmNode = n.optional("slurm");
      Optional<SlurmConfig> slurm = Optional.empty();
      if (slurmNode.hasValue()) {
        slurm = Optional.ofNullable(slurm(slurmNode));
      } else if (slurmNode.isAbsent() && type == ExecutorType.SLURM) {
        slurmNode.error("required when executor.type is slurm");
      }
      if (errors.size() > mark) {
        return null;
      }
      Optional<SlurmConfig> slurmConfig = slurm;
      return build(n, () -> ExecutorConfig.of(type, maxRetries, slurmConfig));
    }

    private SlurmConfig slurm(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("partition", "gres", "time", "requeue_on_preempt", "extra_sbatch_args")) {
        return null;
      }
      Optional<String> partition = optionalString(n.optional("partition"));
      Optional<String> gres = optionalString(n.optional("gres"));
      Optional<String> time = slurmTime(n.optional("time"));
      Boolean requeue = n.required("requeue_on_preempt").asBoolean();
      ConfigNode extraNode = n.optional("extra_sbatch_args");
      List<String> extra = new ArrayList<>();
      for (ConfigNode element : extraNode.elements()) {
        extra.add(element.asString());
      }
      if (errors.size() > mark) {
        return null;
      }
      return build(n, () -> SlurmConfig.of(partition, gres, time, requeue, extra));
    }

    private Optional<String> optionalString(ConfigNode n) {
      return n.isAbsent() ? Optional.empty() : Optional.ofNullable(n.asString());
    }

    private Optional<String> slurmTime(ConfigNode n) {
      if (n.isAbsent()) {
        return Optional.empty();
      }
      if (n.isNumber()) {
        n.error(
            "must be a quoted string such as \"00:45:00\"; YAML can read unquoted times as"
                + " numbers");
        return Optional.empty();
      }
      String text = n.asString();
      if (text != null && !SLURM_TIME.matcher(text).matches()) {
        n.error(
            "must be in an sbatch --time format (minutes, minutes:seconds,"
                + " hours:minutes:seconds, days-hours, days-hours:minutes,"
                + " days-hours:minutes:seconds), was '"
                + text
                + "'");
        return Optional.empty();
      }
      return Optional.ofNullable(text);
    }

    private HarnessConfig harness(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("command")) {
        return null;
      }
      ConfigNode commandNode = n.required("command");
      if (commandNode.isText()) {
        commandNode.error(
            "must be a list of arguments, such as [\"python\", \"python/measure_acceptance.py\"]");
        return null;
      }
      List<String> command = new ArrayList<>();
      for (ConfigNode element : commandNode.elements("must not be empty")) {
        command.add(element.asString());
      }
      if (errors.size() > mark) {
        return null;
      }
      return build(n, () -> HarnessConfig.of(command));
    }

    // --- probes -------------------------------------------------------------------------------

    private List<Probe> probes(ConfigNode n, Set<String> declaredIds) {
      List<Probe> probes = new ArrayList<>();
      List<ConfigNode> elements = n.elements("must not be empty");
      for (ConfigNode element : elements) {
        String id = element.hasValue() ? element.peekName("id") : null;
        if (id != null && !declaredIds.add(id)) {
          element.optional("id").error("duplicate probe id '" + id + "'");
        }
        Probe probe = probe(element);
        if (probe != null) {
          probes.add(probe);
        }
      }
      return probes;
    }

    private Probe probe(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("id", "draft", "prompts", "decoding", "estimator", "seeds")) {
        return null;
      }
      String id = n.required("id").asName();
      Draft draft = draft(n.required("draft"));
      Path prompts = prompts(n.required("prompts"));
      Decoding decoding = decoding(n.required("decoding"));
      ConfigNode estimatorNode = n.optional("estimator");
      Estimator estimator =
          estimatorNode.isAbsent()
              ? Estimator.TOKEN_WEIGHTED
              : estimatorNode.asEnum(Estimator.class);
      ConfigNode seedsNode = n.required("seeds");
      List<Integer> seeds = seeds(seedsNode);
      if (decoding != null && seeds != null && decoding.isGreedy() && seeds.size() > 1) {
        seedsNode.error(
            "temperature is 0, so decoding is greedy and seeds do not change the output; use"
                + " exactly one seed");
      }
      if (errors.size() > mark) {
        return null;
      }
      return build(n, () -> Probe.of(id, draft, prompts, decoding, estimator, seeds));
    }

    private Draft draft(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("id", "path", "structure")) {
        return null;
      }
      String id = n.required("id").asName();
      Path path = n.required("path").asPath(baseDir);
      DraftStructure structure =
          n.required("structure")
              .asEnum(
                  DraftStructure.class,
                  "tree drafting is not supported in v1 (DECISIONS.md D5)");
      if (errors.size() > mark) {
        return null;
      }
      return build(n, () -> Draft.of(id, path, structure));
    }

    private Path prompts(ConfigNode n) {
      if (!n.mapping("path")) {
        return null;
      }
      return n.required("path").asPath(baseDir);
    }

    private Decoding decoding(ConfigNode n) {
      int mark = errors.size();
      if (!n.mapping("temperature", "max_new_tokens", "num_speculative_tokens", "dtype")) {
        return null;
      }
      ConfigNode temperatureNode = n.required("temperature");
      BigDecimal temperature = temperatureNode.asDecimal();
      if (temperature != null && temperature.signum() < 0) {
        temperatureNode.error("must be >= 0, was " + temperature.toPlainString());
      }
      Integer maxNewTokens = n.required("max_new_tokens").asInt(1);
      Integer numSpeculative = n.required("num_speculative_tokens").asInt(1);
      String dtype = n.required("dtype").asString();
      if (errors.size() > mark) {
        return null;
      }
      return build(n, () -> Decoding.of(temperature, maxNewTokens, numSpeculative, dtype));
    }

    private List<Integer> seeds(ConfigNode n) {
      int mark = errors.size();
      List<ConfigNode> elements = n.elements("must not be empty");
      List<Integer> seeds = new ArrayList<>();
      Set<Integer> seen = new HashSet<>();
      for (ConfigNode element : elements) {
        Integer seed = element.asInt(0);
        if (seed != null && !seen.add(seed)) {
          element.error("duplicate seed " + seed);
        }
        seeds.add(seed);
      }
      return errors.size() > mark || !n.hasValue() ? null : seeds;
    }

    // --- targets ------------------------------------------------------------------------------

    private List<TargetConfig> targets(ConfigNode n, Set<String> declaredProbeIds) {
      List<TargetConfig> targets = new ArrayList<>();
      List<ConfigNode> elements = n.elements("must not be empty");
      Set<String> names = new HashSet<>();
      for (ConfigNode element : elements) {
        String name = element.hasValue() ? element.peekName("name") : null;
        if (name != null && !names.add(name)) {
          element.optional("name").error("duplicate target name '" + name + "'");
        }
        TargetConfig target = target(element, declaredProbeIds);
        if (target != null) {
          targets.add(target);
        }
      }
      return targets;
    }

    private TargetConfig target(ConfigNode n, Set<String> declaredProbeIds) {
      int mark = errors.size();
      if (!n.mapping(
          "name",
          "checkpoint_dirs",
          "checkpoint_type",
          "base_model",
          "completion",
          "step_regex",
          "final_marker",
          "probes",
          "triggers",
          "detectors",
          "on_regression")) {
        return null;
      }
      Target.Builder target = Target.builder();
      target.name(n.required("name").asName());
      target.checkpointDirs(checkpointDirs(n.required("checkpoint_dirs")));
      CheckpointType type = n.required("checkpoint_type").asEnum(CheckpointType.class);
      target.checkpointType(type);
      baseModel(n.optional("base_model"), type, target);
      stepRegex(n.optional("step_regex"), target);
      ConfigNode finalNode = n.optional("final_marker");
      if (!finalNode.isAbsent()) {
        target.finalMarker(finalNode.asFileName());
      }
      ConfigNode completionNode = n.optional("completion");
      CompletionSpec completion =
          completionNode.isAbsent() ? CompletionSpec.defaultSpec() : completion(completionNode);
      List<String> probeIds = probeRefs(n.required("probes"), declaredProbeIds);
      ConfigNode triggersNode = n.optional("triggers");
      List<TriggerSpec> triggers =
          triggersNode.isAbsent() ? TriggerSpec.defaultChain() : triggers(triggersNode);
      ConfigNode detectorsNode = n.optional("detectors");
      List<DetectorSpec> detectors =
          detectorsNode.isAbsent()
              ? List.of(PairedBootstrapSpec.withDefaults(Metric.ALPHA))
              : detectors(detectorsNode);
      ConfigNode actionsNode = n.optional("on_regression");
      List<ActionKind> actions =
          actionsNode.isAbsent() ? List.of(ActionKind.NOTIFY) : actions(actionsNode);
      if (errors.size() > mark) {
        return null;
      }
      Target built = build(n, target::build);
      if (built == null) {
        return null;
      }
      return build(
          n, () -> TargetConfig.of(built, completion, probeIds, triggers, detectors, actions));
    }

    private List<Path> checkpointDirs(ConfigNode n) {
      List<ConfigNode> elements = n.elements("must not be empty");
      List<Path> dirs = new ArrayList<>();
      Set<Path> seen = new HashSet<>();
      for (ConfigNode element : elements) {
        Path dir = element.asPath(baseDir);
        if (dir != null && !seen.add(dir)) {
          element.error("duplicate directory " + dir);
        }
        dirs.add(dir);
      }
      return dirs;
    }

    private void baseModel(ConfigNode n, CheckpointType type, Target.Builder target) {
      if (type == CheckpointType.ADAPTER && n.isAbsent()) {
        n.error("required when checkpoint_type is adapter: the harness merges the adapter into it");
      } else if (type == CheckpointType.FULL && !n.isAbsent()) {
        n.error("only valid when checkpoint_type is adapter");
      } else if (n.hasValue()) {
        Path base = n.asPath(baseDir);
        if (base != null) {
          target.baseModel(base);
        }
      }
    }

    private void stepRegex(ConfigNode n, Target.Builder target) {
      if (n.isAbsent()) {
        return;
      }
      String text = n.asString();
      if (text == null) {
        return;
      }
      try {
        Pattern pattern = Pattern.compile(text);
        if (pattern.matcher("").groupCount() < 1) {
          n.error("must have a capture group; group 1 is the step");
          return;
        }
        target.stepRegex(pattern);
      } catch (PatternSyntaxException e) {
        n.error("is not a valid regular expression: " + e.getDescription());
      }
    }

    private CompletionSpec completion(ConfigNode n) {
      if (!n.mapping("marker", "settle_seconds")) {
        return null;
      }
      ConfigNode marker = n.optional("marker");
      ConfigNode settle = n.optional("settle_seconds");
      if (marker.isAbsent() == settle.isAbsent()) {
        n.error("set exactly one of marker, settle_seconds");
        return null;
      }
      if (!marker.isAbsent()) {
        String name = marker.asFileName();
        if (INDEX_FILE.equals(name)) {
          marker.error(
              "must not be "
                  + INDEX_FILE
                  + ": it is absent for single-file checkpoints and its write order relative to"
                  + " the shards is not guaranteed (SPEC.md F1)");
          return null;
        }
        return name == null ? null : build(marker, () -> CompletionSpec.marker(name));
      }
      Integer seconds = settle.asInt(1);
      return seconds == null ? null : build(settle, () -> CompletionSpec.settleSeconds(seconds));
    }

    private List<String> probeRefs(ConfigNode n, Set<String> declaredProbeIds) {
      List<ConfigNode> elements = n.elements("must not be empty");
      List<String> ids = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (ConfigNode element : elements) {
        String id = element.asName();
        if (id == null) {
          continue;
        }
        if (!declaredProbeIds.contains(id)) {
          element.error(
              "unknown probe '"
                  + id
                  + "' (declared: "
                  + String.join(", ", declaredProbeIds)
                  + ")");
        } else if (!seen.add(id)) {
          element.error("duplicate probe '" + id + "'");
        }
        ids.add(id);
      }
      return ids;
    }

    private List<TriggerSpec> triggers(ConfigNode n) {
      int mark = errors.size();
      List<ConfigNode> elements =
          n.elements("must not be empty; omit the key to use the default chain");
      List<TriggerSpec> chain = new ArrayList<>();
      List<ConfigNode> chainNodes = new ArrayList<>();
      Set<TriggerSpec.Kind> seen = new HashSet<>();
      for (ConfigNode element : elements) {
        Map.Entry<String, ConfigNode> entry = element.singleKey("trigger rule");
        if (entry == null) {
          continue;
        }
        TriggerSpec.Kind kind =
            WireNamed.parse(TriggerSpec.Kind.class, entry.getKey()).orElse(null);
        if (kind == null) {
          element.error(
              "unknown trigger rule '"
                  + entry.getKey()
                  + "' (known: "
                  + WireNamed.allNames(TriggerSpec.Kind.class)
                  + ")");
          continue;
        }
        if (!seen.add(kind)) {
          element.error("duplicate trigger rule " + kind.wireName());
          continue;
        }
        TriggerSpec spec = triggerSpec(kind, entry.getValue());
        if (spec != null) {
          chain.add(spec);
          chainNodes.add(element);
        }
      }
      if (errors.size() > mark) {
        return null;
      }
      int notMeasured = indexOf(chain, TriggerSpec.Kind.NOT_ALREADY_MEASURED);
      int alwaysFinal = indexOf(chain, TriggerSpec.Kind.ALWAYS_FINAL);
      if (notMeasured < 0) {
        n.error(
            "must include not_already_measured; without it every poll re-submits checkpoints"
                + " that were already measured (DECISIONS.md D22)");
        return null;
      }
      if (alwaysFinal >= 0 && alwaysFinal < notMeasured) {
        chainNodes
            .get(alwaysFinal)
            .error(
                "always_final is before not_already_measured, which re-measures final"
                    + " checkpoints forever; put not_already_measured first (SPEC.md F2)");
        return null;
      }
      return chain;
    }

    private static int indexOf(List<TriggerSpec> chain, TriggerSpec.Kind kind) {
      for (int i = 0; i < chain.size(); i++) {
        if (chain.get(i).kind() == kind) {
          return i;
        }
      }
      return -1;
    }

    private TriggerSpec triggerSpec(TriggerSpec.Kind kind, ConfigNode value) {
      if (kind.takesArgument()) {
        Integer argument = value.asInt(1);
        return argument == null ? null : build(value, () -> TriggerSpec.of(kind, argument));
      }
      if (!value.emptyMapping(
          "takes no parameters; write '" + kind.wireName() + ": {}'")) {
        return null;
      }
      return build(value, () -> TriggerSpec.of(kind));
    }

    private List<DetectorSpec> detectors(ConfigNode n) {
      int mark = errors.size();
      List<ConfigNode> elements =
          n.elements("must not be empty; omit the key to use the default detector");
      List<DetectorSpec> detectors = new ArrayList<>();
      for (ConfigNode element : elements) {
        Map.Entry<String, ConfigNode> entry = element.singleKey("detector");
        if (entry == null) {
          continue;
        }
        DetectorSpec.Kind kind =
            WireNamed.parse(DetectorSpec.Kind.class, entry.getKey()).orElse(null);
        if (kind == null) {
          element.error(
              "unknown detector '"
                  + entry.getKey()
                  + "' (known: "
                  + WireNamed.allNames(DetectorSpec.Kind.class)
                  + ")");
          continue;
        }
        DetectorSpec spec = detectorSpec(kind, entry.getValue());
        if (spec != null) {
          detectors.add(spec);
        }
      }
      return errors.size() > mark ? null : detectors;
    }

    private DetectorSpec detectorSpec(DetectorSpec.Kind kind, ConfigNode p) {
      int mark = errors.size();
      switch (kind) {
        case PAIRED_BOOTSTRAP:
          {
            if (!p.mapping("metric", "confidence", "min_effect", "resamples", "bootstrap_seed")) {
              return null;
            }
            Metric metric = p.required("metric").asEnum(Metric.class);
            ConfigNode confidenceNode = p.optional("confidence");
            Double confidence =
                optionalDouble(confidenceNode, PairedBootstrapSpec.DEFAULT_CONFIDENCE);
            if (confidence != null && !(confidence > 0 && confidence < 1)) {
              confidenceNode.error("must be > 0 and < 1, was " + SpecText.number(confidence));
            }
            ConfigNode minEffectNode = p.optional("min_effect");
            Double minEffect =
                optionalDouble(minEffectNode, PairedBootstrapSpec.DEFAULT_MIN_EFFECT);
            requireAtLeast(minEffectNode, minEffect, 0);
            ConfigNode resamplesNode = p.optional("resamples");
            Integer resamples =
                resamplesNode.isAbsent()
                    ? Integer.valueOf(PairedBootstrapSpec.DEFAULT_RESAMPLES)
                    : resamplesNode.asInt(1);
            ConfigNode seedNode = p.optional("bootstrap_seed");
            Long seed =
                seedNode.isAbsent()
                    ? Long.valueOf(PairedBootstrapSpec.DEFAULT_BOOTSTRAP_SEED)
                    : seedNode.asLong(Long.MIN_VALUE);
            if (errors.size() > mark) {
              return null;
            }
            return build(
                p, () -> PairedBootstrapSpec.of(metric, confidence, minEffect, resamples, seed));
          }
        case ABSOLUTE_DROP:
          {
            if (!p.mapping("metric", "max_drop")) {
              return null;
            }
            Metric metric = p.required("metric").asEnum(Metric.class);
            ConfigNode dropNode = p.required("max_drop");
            Double maxDrop = dropNode.asDouble();
            requireAtLeast(dropNode, maxDrop, 0);
            if (errors.size() > mark) {
              return null;
            }
            return build(p, () -> AbsoluteDropSpec.of(metric, maxDrop));
          }
        case NOISE_FLOOR:
          {
            if (!p.mapping("metric", "k", "sigma")) {
              return null;
            }
            Metric metric = p.required("metric").asEnum(Metric.class);
            ConfigNode kNode = p.required("k");
            Double k = kNode.asDouble();
            requirePositive(kNode, k);
            ConfigNode sigmaNode =
                p.required(
                    "sigma",
                    "required: the standard deviation of a single measurement from an external"
                        + " source such as replicate training runs; it has no default (SPEC.md"
                        + " F5)");
            Double sigma = sigmaNode.asDouble();
            requirePositive(sigmaNode, sigma);
            if (errors.size() > mark) {
              return null;
            }
            return build(p, () -> NoiseFloorSpec.of(metric, k, sigma));
          }
        case TREND:
          {
            if (!p.mapping("metric", "window", "max_slope")) {
              return null;
            }
            Metric metric = p.required("metric").asEnum(Metric.class);
            Integer window = p.required("window").asInt(2);
            Double maxSlope = p.required("max_slope").asDouble();
            if (errors.size() > mark) {
              return null;
            }
            return build(p, () -> TrendSpec.of(metric, window, maxSlope));
          }
        default:
          throw new IllegalStateException("unhandled detector kind " + kind);
      }
    }

    /** The node's number, {@code fallback} if absent, or null after reporting an error. */
    private static Double optionalDouble(ConfigNode n, double fallback) {
      return n.isAbsent() ? Double.valueOf(fallback) : n.asDouble();
    }

    /** Reports {@code value < min} at {@code n}; a null value was already reported. */
    private static void requireAtLeast(ConfigNode n, Double value, double min) {
      if (value != null && value < min) {
        n.error("must be >= " + SpecText.number(min) + ", was " + SpecText.number(value));
      }
    }

    /** Reports {@code value <= 0} at {@code n}; a null value was already reported. */
    private static void requirePositive(ConfigNode n, Double value) {
      if (value != null && value <= 0) {
        n.error("must be > 0, was " + SpecText.number(value));
      }
    }

    private List<ActionKind> actions(ConfigNode n) {
      List<ActionKind> actions = new ArrayList<>();
      Set<ActionKind> seen = new HashSet<>();
      for (ConfigNode element : n.elements()) {
        ActionKind action =
            element.asEnum(ActionKind.class, "retrain_draft arrives with M7 (docs/ROADMAP.md)");
        if (action != null && !seen.add(action)) {
          element.error("duplicate action " + action.wireName());
        }
        actions.add(action);
      }
      return actions;
    }
  }
}
