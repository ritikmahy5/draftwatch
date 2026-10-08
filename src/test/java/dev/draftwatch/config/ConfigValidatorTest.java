package dev.draftwatch.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.fingerprint.FingerprintMethod;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/**
 * One test per validation rule: each starts from {@code configs/valid.yaml}, breaks exactly one
 * thing, and asserts the error names the offending field.
 */
public class ConfigValidatorTest {
  private static final ObjectMapper YAML =
      YAMLMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Path CONFIG_FILE = Paths.get("/work/draftwatch.yaml");

  private final ConfigValidator validator = new ConfigValidator();
  private ObjectNode root;

  @Before
  public void setUp() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/configs/valid.yaml")) {
      root = (ObjectNode) YAML.readTree(in);
    }
  }

  private ObjectNode probe(int i) {
    return (ObjectNode) root.get("probes").get(i);
  }

  private ObjectNode target(int i) {
    return (ObjectNode) root.get("targets").get(i);
  }

  private ObjectNode executor() {
    return (ObjectNode) root.get("executor");
  }

  private static JsonNode yaml(String text) {
    try {
      return YAML.readTree(text);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private DraftwatchConfig valid() {
    return validator.validate(root, CONFIG_FILE);
  }

  private List<ConfigError> errors() {
    try {
      validator.validate(root, CONFIG_FILE);
      fail("expected ConfigException");
      return null;
    } catch (ConfigException e) {
      assertEquals(CONFIG_FILE, e.file());
      return e.errors();
    }
  }

  /** Asserts exactly one error, at {@code field}, whose message contains {@code fragment}. */
  private void assertOnlyError(String field, String fragment) {
    List<ConfigError> errors = errors();
    assertEquals("errors: " + errors, 1, errors.size());
    assertEquals(field, errors.get(0).field());
    assertTrue(errors.get(0).toString(), errors.get(0).message().contains(fragment));
  }

  // --- the valid config ---------------------------------------------------------------------

  @Test
  public void validConfigResolvesPathsAgainstConfigDirectory() {
    DraftwatchConfig c = valid();
    assertEquals(CONFIG_FILE, c.configFile());
    assertEquals(Paths.get("/work/state"), c.stateDir());
    Probe p = c.probe("chat-default").orElseThrow();
    assertEquals(Paths.get("/work/drafts/my-draft"), p.draft().path());
    assertEquals(Paths.get("/work/probes/chat.jsonl"), p.promptsPath());
    TargetConfig sft = c.target("my-sft-run").orElseThrow();
    assertEquals(List.of(Paths.get("/work/runs/sft/checkpoints")), sft.target().checkpointDirs());
    TargetConfig lora = c.target("my-lora-run").orElseThrow();
    assertEquals(List.of(Paths.get("/abs/lora/checkpoints")), lora.target().checkpointDirs());
    assertEquals(Optional.of(Paths.get("/work/models/base")), lora.target().baseModel());
  }

  @Test
  public void validConfigKeepsEveryConfiguredValue() {
    DraftwatchConfig c = valid();
    assertEquals(FingerprintMethod.SAMPLED, c.fingerprintMethod());
    assertEquals(ExecutorType.SLURM, c.executor().type());
    assertEquals(2, c.executor().maxRetries());
    SlurmConfig slurm = c.executor().slurm().orElseThrow();
    assertEquals(Optional.of("gpu"), slurm.partition());
    assertEquals(Optional.of("gpu:1"), slurm.gres());
    assertEquals(Optional.of("00:45:00"), slurm.time());
    assertTrue(slurm.requeueOnPreempt());
    assertEquals(List.of("--mem=32G"), slurm.extraSbatchArgs());
    assertEquals(List.of("--time=00:30:00"), slurm.scheduleSbatchArgs());
    assertEquals(List.of("python", "python/measure_acceptance.py"), c.harness().command());

    TargetConfig sft = c.target("my-sft-run").orElseThrow();
    assertEquals(CompletionSpec.marker("DONE"), sft.completion());
    assertEquals("step_(\\d+)$", sft.target().stepRegex().pattern());
    assertEquals("LAST", sft.target().finalMarker());
    assertEquals(List.of("chat-default", "chat-sampled"), sft.probeIds());
    assertEquals(
        List.of(
            TriggerSpec.of(TriggerSpec.Kind.NOT_ALREADY_MEASURED),
            TriggerSpec.of(TriggerSpec.Kind.MAX_PENDING, 4),
            TriggerSpec.of(TriggerSpec.Kind.ALWAYS_FINAL),
            TriggerSpec.of(TriggerSpec.Kind.EVERY_N_STEPS, 500)),
        sft.triggers());
    assertEquals(
        List.of(
            PairedBootstrapSpec.of(Metric.ALPHA, 0.9, 0.01, 500, 7),
            AbsoluteDropSpec.of(Metric.ALPHA, 0.05),
            NoiseFloorSpec.of(Metric.TAU, 2, 0.01),
            TrendSpec.of(Metric.TAU, 4, -0.02)),
        sft.detectors());
    assertEquals(List.of(ActionSpec.notifyAction()), sft.onRegression());

    Probe sampled = c.probe("chat-sampled").orElseThrow();
    assertEquals(new BigDecimal("0.7"), sampled.decoding().temperature());
    assertEquals(List.of(0, 1, 2), sampled.seeds());
  }

  @Test
  public void omittedKeysTakeTheDocumentedDefaults() {
    root.remove("state_dir");
    root.remove("fingerprint");
    probe(0).remove("estimator");
    DraftwatchConfig c = valid();
    assertEquals(Paths.get("/work/.draftwatch"), c.stateDir());
    assertEquals(FingerprintMethod.SAMPLED, c.fingerprintMethod());
    assertEquals(Estimator.TOKEN_WEIGHTED, c.probe("chat-default").orElseThrow().estimator());

    TargetConfig lora = c.target("my-lora-run").orElseThrow();
    assertEquals(CheckpointType.ADAPTER, lora.target().checkpointType());
    assertEquals(CompletionSpec.settleSeconds(120), lora.completion());
    assertEquals("checkpoint-(\\d+)$", lora.target().stepRegex().pattern());
    assertEquals("FINAL", lora.target().finalMarker());
    assertEquals(TriggerSpec.defaultChain(), lora.triggers());
    assertEquals(List.of(PairedBootstrapSpec.withDefaults(Metric.ALPHA)), lora.detectors());
    assertEquals("regressions are alerted unless on_regression says otherwise",
        List.of(ActionSpec.notifyAction()), lora.onRegression());
  }

  @Test
  public void pairedBootstrapParametersDefaultPerSpec() {
    target(0).set("detectors", yaml("[{paired_bootstrap: {metric: tau}}]"));
    assertEquals(
        List.of(PairedBootstrapSpec.of(Metric.TAU, 0.95, 0.0, 2000, 0)),
        valid().target("my-sft-run").orElseThrow().detectors());
  }

  @Test
  public void localExecutorNeedsNoSlurmBlock() {
    executor().put("type", "local");
    executor().remove("slurm");
    assertEquals(Optional.empty(), valid().executor().slurm());
  }

  // --- validation rules -----------------------------------------------------------------------

  @Test
  public void unknownEstimatorIsRejected() {
    probe(0).put("estimator", "median");
    assertOnlyError(
        "probes[0].estimator", "must be one of token_weighted | simple_mean, was 'median'");
  }

  @Test
  public void greedyDecodingWithMoreThanOneSeedIsRejected() {
    probe(0).set("seeds", yaml("[0, 1]"));
    assertOnlyError("probes[0].seeds", "temperature is 0");
  }

  @Test
  public void greedyRuleAppliesToZeroWrittenAsDecimal() {
    ((ObjectNode) probe(0).get("decoding")).put("temperature", new BigDecimal("0.0"));
    probe(0).set("seeds", yaml("[0, 1]"));
    assertOnlyError("probes[0].seeds", "temperature is 0");
  }

  @Test
  public void adapterWithoutBaseModelIsRejected() {
    target(1).remove("base_model");
    assertOnlyError("targets[1].base_model", "required when checkpoint_type is adapter");
  }

  @Test
  public void alwaysFinalBeforeNotAlreadyMeasuredIsRejected() {
    target(0)
        .set(
            "triggers",
            yaml("[{always_final: {}}, {not_already_measured: {}}, {every_n_steps: 500}]"));
    assertOnlyError("targets[0].triggers[0]", "always_final is before not_already_measured");
  }

  @Test
  public void noiseFloorWithoutSigmaIsRejected() {
    ((ObjectNode) target(0).get("detectors").get(2).get("noise_floor")).remove("sigma");
    assertOnlyError(
        "targets[0].detectors[2].noise_floor.sigma",
        "required: the standard deviation of a single measurement");
  }

  @Test
  public void draftStructureOtherThanChainIsRejected() {
    ((ObjectNode) probe(0).get("draft")).put("structure", "tree");
    assertOnlyError(
        "probes[0].draft.structure",
        "must be one of chain, was 'tree'; tree drafting is not supported");
  }

  // --- trigger chain -------------------------------------------------------------------------

  @Test
  public void chainWithoutNotAlreadyMeasuredIsRejected() {
    target(0).set("triggers", yaml("[{always_final: {}}, {every_n_steps: 500}]"));
    assertOnlyError("targets[0].triggers", "must include not_already_measured");
  }

  @Test
  public void notAlreadyMeasuredMayFollowMaxPending() {
    target(0)
        .set(
            "triggers",
            yaml("[{max_pending: 2}, {not_already_measured: {}}, {always_final: {}}]"));
    assertEquals(3, valid().target("my-sft-run").orElseThrow().triggers().size());
  }

  @Test
  public void unknownAndDuplicateTriggerRulesAreRejected() {
    target(0)
        .set(
            "triggers",
            yaml("[{not_already_measured: {}}, {every_hour: {}}, {not_already_measured: {}}]"));
    List<String> fields =
        errors().stream().map(ConfigError::field).collect(Collectors.toList());
    assertEquals(List.of("targets[0].triggers[1]", "targets[0].triggers[2]"), fields);
  }

  @Test
  public void triggerArgumentsAreChecked() {
    target(0)
        .set(
            "triggers",
            yaml("[{not_already_measured: 3}, {max_pending: 0}, {every_n_steps: 2.5}]"));
    List<ConfigError> errors = errors();
    assertEquals(3, errors.size());
    assertEquals("targets[0].triggers[0].not_already_measured", errors.get(0).field());
    assertTrue(errors.get(0).message().contains("takes no parameters"));
    assertEquals("targets[0].triggers[1].max_pending", errors.get(1).field());
    assertTrue(errors.get(1).message().contains("must be >= 1, was 0"));
    assertEquals("targets[0].triggers[2].every_n_steps", errors.get(2).field());
    assertTrue(errors.get(2).message().contains("must be an integer"));
  }

  @Test
  public void triggerItemMustBeOneKeyMapping() {
    target(0).set("triggers", yaml("[not_already_measured]"));
    assertOnlyError("targets[0].triggers[0]", "must be a mapping with exactly one key");
  }

  @Test
  public void emptyTriggerListIsRejected() {
    target(0).set("triggers", NODES.arrayNode());
    assertOnlyError("targets[0].triggers", "omit the key to use the default chain");
  }

  // --- detectors -----------------------------------------------------------------------------

  @Test
  public void detectorParametersAreRangeChecked() {
    target(0)
        .set(
            "detectors",
            yaml(
                "[{paired_bootstrap: {metric: alpha, confidence: 1.0}},"
                    + " {trend: {metric: tau, window: 1, max_slope: 0}},"
                    + " {noise_floor: {metric: tau, k: 2, sigma: 0}},"
                    + " {absolute_drop: {metric: alpha, max_drop: -0.1}}]"));
    List<ConfigError> errors = errors();
    assertEquals(
        List.of(
            "targets[0].detectors[0].paired_bootstrap.confidence",
            "targets[0].detectors[1].trend.window",
            "targets[0].detectors[2].noise_floor.sigma",
            "targets[0].detectors[3].absolute_drop.max_drop"),
        errors.stream().map(ConfigError::field).collect(Collectors.toList()));
  }

  @Test
  public void nonNumericDetectorParametersAreReportedNotThrown() {
    target(0)
        .set(
            "detectors",
            yaml(
                "[{paired_bootstrap: {metric: alpha, confidence: high, min_effect: none}},"
                    + " {noise_floor: {metric: tau, k: two, sigma: [1]}}]"));
    List<ConfigError> errors = errors();
    assertEquals(
        List.of(
            "targets[0].detectors[0].paired_bootstrap.confidence",
            "targets[0].detectors[0].paired_bootstrap.min_effect",
            "targets[0].detectors[1].noise_floor.k",
            "targets[0].detectors[1].noise_floor.sigma"),
        errors.stream().map(ConfigError::field).collect(Collectors.toList()));
    for (ConfigError error : errors) {
      assertEquals(error.toString(), "must be a number", error.message());
    }
  }

  @Test
  public void detectorMetricMustBeAlphaOrTau() {
    target(0)
        .set("detectors", yaml("[{absolute_drop: {metric: alpha_by_position, max_drop: 0}}]"));
    assertOnlyError(
        "targets[0].detectors[0].absolute_drop.metric", "must be one of alpha | tau");
  }

  @Test
  public void unknownDetectorAndUnknownParameterAreRejected() {
    target(0)
        .set(
            "detectors",
            yaml("[{t_test: {metric: alpha}}, {trend: {metric: tau, window: 4, max_slope: 0,"
                + " windw: 3}}]"));
    List<ConfigError> errors = errors();
    assertEquals(2, errors.size());
    assertEquals("targets[0].detectors[0]", errors.get(0).field());
    assertTrue(errors.get(0).message().contains("unknown detector 't_test'"));
    assertEquals("targets[0].detectors[1].trend.windw", errors.get(1).field());
    assertTrue(errors.get(1).message().contains("unknown key"));
  }

  // --- targets -------------------------------------------------------------------------------

  @Test
  public void fullCheckpointWithBaseModelIsRejected() {
    target(0).put("base_model", "models/base");
    assertOnlyError("targets[0].base_model", "only valid when checkpoint_type is adapter");
  }

  @Test
  public void missingCheckpointTypeIsRejected() {
    target(0).remove("checkpoint_type");
    assertOnlyError("targets[0].checkpoint_type", "required");
  }

  @Test
  public void indexFileAsMarkerIsRejected() {
    target(0).set("completion", yaml("{marker: model.safetensors.index.json}"));
    assertOnlyError("targets[0].completion.marker", "must not be model.safetensors.index.json");
  }

  @Test
  public void completionNeedsExactlyOnePolicy() {
    target(0).set("completion", yaml("{marker: DONE, settle_seconds: 60}"));
    assertOnlyError("targets[0].completion", "set exactly one of marker, settle_seconds");
    setUpQuietly();
    target(0).set("completion", NODES.objectNode());
    assertOnlyError("targets[0].completion", "set exactly one of marker, settle_seconds");
  }

  @Test
  public void markerMustBeAFileName() {
    target(0).set("completion", yaml("{marker: sub/DONE}"));
    assertOnlyError("targets[0].completion.marker", "must be a file name, not a path");
  }

  @Test
  public void settleSecondsMustBePositive() {
    target(0).set("completion", yaml("{settle_seconds: 0}"));
    assertOnlyError("targets[0].completion.settle_seconds", "must be >= 1, was 0");
  }

  @Test
  public void stepRegexMustCompileAndHaveAGroup() {
    target(0).put("step_regex", "checkpoint-(\\d+");
    assertOnlyError("targets[0].step_regex", "is not a valid regular expression");
    setUpQuietly();
    target(0).put("step_regex", "checkpoint-\\d+$");
    assertOnlyError("targets[0].step_regex", "must have a capture group");
  }

  @Test
  public void unknownProbeReferenceIsRejected() {
    target(1).set("probes", yaml("[chat-defualt]"));
    assertOnlyError(
        "targets[1].probes[0]",
        "unknown probe 'chat-defualt' (declared: chat-default, chat-sampled)");
  }

  @Test
  public void duplicateNamesAreRejected() {
    target(1).put("name", "my-sft-run");
    assertOnlyError("targets[1].name", "duplicate target name 'my-sft-run'");
    setUpQuietly();
    probe(1).put("id", "chat-default");
    target(0).set("probes", yaml("[chat-default]"));
    assertOnlyError("probes[1].id", "duplicate probe id 'chat-default'");
  }

  @Test
  public void namesMustBePathSafe() {
    target(0).put("name", "../escape");
    assertOnlyError("targets[0].name", "must start with a letter or digit");
  }

  @Test
  public void emptyOnRegressionMeansNoActions() {
    target(0).set("on_regression", NODES.arrayNode());
    assertEquals(List.of(), valid().target("my-sft-run").orElseThrow().onRegression());
  }

  @Test
  public void retrainDraftTakesACommand() {
    target(0).set(
        "on_regression", yaml("[notify, {retrain_draft: {command: [python, train_draft.py]}}]"));
    assertEquals(
        List.of(
            ActionSpec.notifyAction(),
            ActionSpec.retrainDraft(RetrainSpec.of(List.of("python", "train_draft.py")))),
        valid().target("my-sft-run").orElseThrow().onRegression());
  }

  @Test
  public void retrainDraftWithoutAUsableCommandIsRejected() {
    String[][] cases = {
      {"[retrain_draft]", "targets[0].on_regression[0]", "needs a command"},
      {"[{retrain_draft: {}}]", "targets[0].on_regression[0].retrain_draft.command", "required"},
      {"[{retrain_draft: {command: []}}]", "targets[0].on_regression[0].retrain_draft.command",
        "must not be empty"},
      {"[{retrain_draft: {command: python train.py}}]",
        "targets[0].on_regression[0].retrain_draft.command", "must be a list of arguments"},
      {"[{retrain_draft: {command: [x], gpus: 1}}]",
        "targets[0].on_regression[0].retrain_draft.gpus", "unknown key"},
      {"[{notify: {}}]", "targets[0].on_regression[0]", "only retrain_draft takes settings"},
      {"[{retrain_draft: {command: [a]}}, {retrain_draft: {command: [b]}}]",
        "targets[0].on_regression[1]", "duplicate action retrain_draft"},
    };
    for (String[] c : cases) {
      setUpQuietly();
      target(0).set("on_regression", yaml(c[0]));
      assertOnlyError(c[1], c[2]);
    }
  }

  @Test
  public void tildeInPathIsRejected() {
    ((ArrayNode) target(0).get("checkpoint_dirs")).set(0, NODES.textNode("~/runs"));
    assertOnlyError("targets[0].checkpoint_dirs[0]", "'~' is not expanded");
  }

  // --- probes --------------------------------------------------------------------------------

  @Test
  public void decodingAcceptsOnlyTheContractKeys() {
    ((ObjectNode) probe(1).get("decoding")).put("top_p", 0.9);
    assertOnlyError("probes[1].decoding.top_p", "unknown key");
  }

  @Test
  public void decodingKeysAreRequiredAndTyped() {
    ObjectNode decoding = (ObjectNode) probe(0).get("decoding");
    decoding.remove("dtype");
    decoding.put("max_new_tokens", 0);
    decoding.put("temperature", -1);
    List<String> fields =
        errors().stream().map(ConfigError::field).collect(Collectors.toList());
    assertEquals(
        List.of(
            "probes[0].decoding.temperature",
            "probes[0].decoding.max_new_tokens",
            "probes[0].decoding.dtype"),
        fields);
  }

  @Test
  public void seedsMustBeDistinctNonNegativeIntegers() {
    probe(1).set("seeds", yaml("[0, 0, -1]"));
    List<ConfigError> errors = errors();
    assertEquals(2, errors.size());
    assertEquals("probes[1].seeds[1]", errors.get(0).field());
    assertTrue(errors.get(0).message().contains("duplicate seed 0"));
    assertEquals("probes[1].seeds[2]", errors.get(1).field());
  }

  @Test
  public void emptySeedsAreRejected() {
    probe(1).set("seeds", NODES.arrayNode());
    assertOnlyError("probes[1].seeds", "must not be empty");
  }

  // --- executor and harness ------------------------------------------------------------------

  @Test
  public void slurmExecutorRequiresSlurmBlockAndRequeuePolicy() {
    executor().remove("slurm");
    assertOnlyError("executor.slurm", "required when executor.type is slurm");
    setUpQuietly();
    ((ObjectNode) executor().get("slurm")).remove("requeue_on_preempt");
    assertOnlyError("executor.slurm.requeue_on_preempt", "required");
  }

  @Test
  public void slurmTimeMustBeAnSbatchFormat() {
    for (String ok : new String[] {"45", "45:30", "1:00:00", "2-0", "2-12:30", "2-12:30:00"}) {
      setUpQuietly();
      ((ObjectNode) executor().get("slurm")).put("time", ok);
      assertEquals(Optional.of(ok), valid().executor().slurm().orElseThrow().time());
    }
    setUpQuietly();
    ((ObjectNode) executor().get("slurm")).put("time", "45m");
    assertOnlyError("executor.slurm.time", "must be in an sbatch --time format");
  }

  private ObjectNode slurm() {
    return (ObjectNode) executor().get("slurm");
  }

  /** Resets the config, then sets {@code executor.slurm.<key>} to {@code args}. */
  private void sbatchArgs(String key, String... args) {
    setUpQuietly();
    ArrayNode list = slurm().putArray(key);
    for (String arg : args) {
      list.add(arg);
    }
  }

  @Test
  public void extraSbatchArgsMayNotSetWhatDraftwatchControls() {
    String[][] refused = {
      {"--output=x.log", "sets --output, which draftwatch sets itself"},
      {"--out=x.log", "sets --output, which draftwatch sets itself"}, // getopt_long abbreviation
      {"-o", "sets --output"},
      {"-ox.log", "sets --output"},
      {"--job-name=mine", "sets --job-name"},
      {"--no-requeue", "sets --no-requeue"},
      {"--wait", "sets --wait"},
      {"--partition=short", "sets --partition; use executor.slurm.partition instead"},
      {"-pshort", "sets --partition; use executor.slurm.partition instead"},
      {"--gres=gpu:2", "sets --gres; use executor.slurm.gres instead"},
      {"-t", "sets --time; use executor.slurm.time instead"},
    };
    for (String[] r : refused) {
      sbatchArgs("extra_sbatch_args", "--mem=32G", r[0]);
      assertOnlyError("executor.slurm.extra_sbatch_args[1]", "'" + r[0] + "' " + r[1]);
    }
    sbatchArgs(
        "extra_sbatch_args",
        "--mem=32G",
        "--mem-per-gpu=8G",
        "--gpus=1",
        "--time-min=10",
        "--wait-all-nodes=1",
        "--gres-flags=enforce-binding",
        "--account=lab");
    assertEquals(7, valid().executor().slurm().orElseThrow().extraSbatchArgs().size());
  }

  @Test
  public void scheduleSbatchArgsMayNotRequestAGpu() {
    String[] gpu = {
      "--gres=gpu:1", "--gpus=1", "-G1", "--gpus-per-node=1", "--gpus-per-task=1",
      "--cpus-per-gpu=4", "--mem-per-gpu=8G", "--gres-flags=enforce-binding", "--gpus-per=1"
    };
    for (String arg : gpu) {
      sbatchArgs("schedule_sbatch_args", arg);
      assertOnlyError(
          "executor.slurm.schedule_sbatch_args[0]", "the schedule job must not request a GPU");
    }
    sbatchArgs("schedule_sbatch_args", "--error=e.log");
    assertOnlyError("executor.slurm.schedule_sbatch_args[0]", "sets --error");
    sbatchArgs("schedule_sbatch_args", "--partition=short", "--time=00:10:00", "--mem=4G");
    assertEquals(
        List.of("--partition=short", "--time=00:10:00", "--mem=4G"),
        valid().executor().slurm().orElseThrow().scheduleSbatchArgs());
    sbatchArgs("schedule_sbatch_args");
    assertEquals(List.of(), valid().executor().slurm().orElseThrow().scheduleSbatchArgs());
  }

  @Test
  public void slurmTimeAsNumberIsRejected() {
    ((ObjectNode) executor().get("slurm")).put("time", 2700);
    assertOnlyError("executor.slurm.time", "must be a quoted string");
  }

  @Test
  public void maxRetriesIsRequiredAndNonNegative() {
    executor().put("max_retries", -1);
    assertOnlyError("executor.max_retries", "must be >= 0, was -1");
    setUpQuietly();
    executor().remove("max_retries");
    assertOnlyError("executor.max_retries", "required");
  }

  @Test
  public void harnessCommandMustBeANonEmptyList() {
    ((ObjectNode) root.get("harness")).put("command", "python measure.py");
    assertOnlyError("harness.command", "must be a list of arguments");
    setUpQuietly();
    ((ObjectNode) root.get("harness")).set("command", NODES.arrayNode());
    assertOnlyError("harness.command", "must not be empty");
  }

  // --- document shape ------------------------------------------------------------------------

  @Test
  public void unknownKeysAreRejectedAtEveryLevel() {
    root.put("statedir", "x");
    ((ObjectNode) target(0).get("completion")).put("settle_second", 5);
    List<ConfigError> errors = errors();
    assertEquals(
        List.of("statedir", "targets[0].completion.settle_second"),
        errors.stream().map(ConfigError::field).collect(Collectors.toList()));
    assertTrue(errors.get(0).message().startsWith("unknown key (allowed: state_dir,"));
  }

  @Test
  public void keyWithoutValueIsRejected() {
    target(1).putNull("base_model");
    assertOnlyError("targets[1].base_model", "has no value");
  }

  @Test
  public void emptyDocumentIsRejected() {
    try {
      validator.validate(NODES.missingNode(), CONFIG_FILE);
      fail("expected ConfigException");
    } catch (ConfigException e) {
      assertEquals("<root>: config file is empty", e.errors().get(0).toString());
    }
  }

  @Test
  public void everyProblemIsReportedInOnePass() {
    probe(0).put("estimator", "median");
    target(1).remove("base_model");
    executor().put("type", "kubernetes");
    List<String> fields =
        errors().stream().map(ConfigError::field).collect(Collectors.toList());
    assertEquals(
        List.of("executor.type", "probes[0].estimator", "targets[1].base_model"), fields);
  }

  @Test
  public void exceptionMessageListsErrorsWithFields() {
    probe(0).put("estimator", "median");
    try {
      valid();
      fail("expected ConfigException");
    } catch (ConfigException e) {
      assertEquals(
          "/work/draftwatch.yaml: 1 error\n  probes[0].estimator: must be one of token_weighted"
              + " | simple_mean, was 'median'",
          e.getMessage());
    }
  }

  /** Restores {@link #root} to the valid config, for tests that check several variants. */
  private void setUpQuietly() {
    try {
      setUp();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
