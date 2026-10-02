package dev.draftwatch.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.AdapterHandling;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Hardware;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.domain.WireNamed;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The report schema of MEASUREMENT_CONTRACT.md ({@code schema_version: 1}) in both directions:
 * the {@code shape} rule over a parsed JSON tree, conversion of a valid tree into an
 * {@link AcceptanceReport}, and conversion back, so stored results use the same layout as the
 * harness's report.
 */
public final class ReportJson {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static final List<String> TOP_KEYS =
      List.of(
          "schema_version",
          "harness_version",
          "backend",
          "adapter_handling",
          "draft_structure",
          "estimator",
          "draft_id",
          "prompt_set_sha256",
          "num_prompts",
          "decoding",
          "seeds",
          "aggregate",
          "hardware",
          "wall_clock_seconds");
  static final List<String> SEED_KEYS =
      List.of(
          "seed",
          "alpha",
          "tau",
          "alpha_by_position",
          "total_steps",
          "total_proposed",
          "total_accepted",
          "excluded_prompts",
          "position_counts_exact",
          "per_prompt",
          "position_counts");
  static final List<String> PROMPT_KEYS = List.of("prompt_index", "steps", "proposed", "accepted");
  static final List<String> POSITION_KEYS = List.of("position", "eligible", "accepted");
  static final List<String> AGGREGATE_KEYS =
      List.of("alpha_mean", "alpha_std", "tau_mean", "tau_std");
  static final List<String> HARDWARE_KEYS = List.of("gpu", "count");

  private ReportJson() {}

  // --- shape ---------------------------------------------------------------------------------

  /**
   * Checks rule {@code shape}: every key present with its type, no other keys, and
   * {@code hardware.count} and {@code wall_clock_seconds} non-negative.
   *
   * @throws ReportViolationException with {@link ReportRule#SHAPE}, naming the field
   */
  public static void checkShape(JsonNode root) {
    keys(root, "", TOP_KEYS);
    integer(root, "", "schema_version");
    text(root, "", "harness_version");
    text(root, "", "backend");
    text(root, "", "adapter_handling");
    text(root, "", "draft_structure");
    text(root, "", "estimator");
    text(root, "", "draft_id");
    text(root, "", "prompt_set_sha256");
    integer(root, "", "num_prompts");
    if (!root.get("decoding").isObject()) {
      throw shape("decoding", "must be an object");
    }
    JsonNode seeds = root.get("seeds");
    if (!seeds.isArray() || seeds.size() == 0) {
      throw shape("seeds", "must be a non-empty array");
    }
    for (int i = 0; i < seeds.size(); i++) {
      seedShape(seeds.get(i), "seeds[" + i + "].");
    }
    JsonNode aggregate = root.get("aggregate");
    keys(aggregate, "aggregate.", AGGREGATE_KEYS);
    number(aggregate, "aggregate.", "alpha_mean");
    numberOrNull(aggregate, "aggregate.", "alpha_std");
    number(aggregate, "aggregate.", "tau_mean");
    numberOrNull(aggregate, "aggregate.", "tau_std");
    JsonNode hardware = root.get("hardware");
    keys(hardware, "hardware.", HARDWARE_KEYS);
    text(hardware, "hardware.", "gpu");
    integer(hardware, "hardware.", "count");
    if (hardware.get("count").intValue() < 0) {
      throw shape("hardware.count", "must be >= 0");
    }
    number(root, "", "wall_clock_seconds");
    if (root.get("wall_clock_seconds").doubleValue() < 0) {
      throw shape("wall_clock_seconds", "must be >= 0");
    }
  }

  private static void seedShape(JsonNode seed, String prefix) {
    keys(seed, prefix, SEED_KEYS);
    integer(seed, prefix, "seed");
    number(seed, prefix, "alpha");
    number(seed, prefix, "tau");
    JsonNode byPosition = seed.get("alpha_by_position");
    if (!byPosition.isArray()) {
      throw shape(prefix + "alpha_by_position", "must be an array");
    }
    for (int j = 0; j < byPosition.size(); j++) {
      JsonNode v = byPosition.get(j);
      if (!v.isNumber() && !v.isNull()) {
        throw shape(prefix + "alpha_by_position[" + j + "]", "must be a number or null");
      }
    }
    longInteger(seed, prefix, "total_steps");
    longInteger(seed, prefix, "total_proposed");
    longInteger(seed, prefix, "total_accepted");
    integer(seed, prefix, "excluded_prompts");
    if (!seed.get("position_counts_exact").isBoolean()) {
      throw shape(prefix + "position_counts_exact", "must be true or false");
    }
    entries(seed, prefix, "per_prompt", PROMPT_KEYS, "prompt_index");
    entries(seed, prefix, "position_counts", POSITION_KEYS, "position");
  }

  /** An array of objects with {@code keys}; the first key is an int, the rest are longs. */
  private static void entries(
      JsonNode seed, String prefix, String field, List<String> keys, String intKey) {
    JsonNode list = seed.get(field);
    if (!list.isArray()) {
      throw shape(prefix + field, "must be an array");
    }
    for (int i = 0; i < list.size(); i++) {
      String at = prefix + field + "[" + i + "].";
      keys(list.get(i), at, keys);
      for (String key : keys) {
        if (key.equals(intKey)) {
          integer(list.get(i), at, key);
        } else {
          longInteger(list.get(i), at, key);
        }
      }
    }
  }

  private static void keys(JsonNode node, String prefix, List<String> expected) {
    String where = prefix.isEmpty() ? "report" : prefix.substring(0, prefix.length() - 1);
    if (node == null || !node.isObject()) {
      throw shape(where, "must be an object");
    }
    for (String key : expected) {
      if (!node.has(key)) {
        throw shape(prefix + key, "is missing");
      }
    }
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!expected.contains(key)) {
        throw shape(prefix + key, "is not in the schema");
      }
    }
  }

  private static void integer(JsonNode node, String prefix, String key) {
    JsonNode v = node.get(key);
    if (!v.isIntegralNumber() || !v.canConvertToInt()) {
      throw shape(prefix + key, "must be an integer");
    }
  }

  private static void longInteger(JsonNode node, String prefix, String key) {
    JsonNode v = node.get(key);
    if (!v.isIntegralNumber() || !v.canConvertToLong()) {
      throw shape(prefix + key, "must be an integer");
    }
  }

  private static void number(JsonNode node, String prefix, String key) {
    if (!node.get(key).isNumber()) {
      throw shape(prefix + key, "must be a number");
    }
  }

  private static void numberOrNull(JsonNode node, String prefix, String key) {
    JsonNode v = node.get(key);
    if (!v.isNumber() && !v.isNull()) {
      throw shape(prefix + key, "must be a number or null");
    }
  }

  private static void text(JsonNode node, String prefix, String key) {
    if (!node.get(key).isTextual()) {
      throw shape(prefix + key, "must be a string");
    }
  }

  private static ReportViolationException shape(String field, String message) {
    return new ReportViolationException(ReportRule.SHAPE, field + " " + message);
  }

  // --- tree to domain ------------------------------------------------------------------------

  /**
   * Converts a report that passed {@link #checkShape} and whose {@code estimator},
   * {@code draft_structure}, {@code adapter_handling}, and {@code decoding} hold valid values.
   *
   * @throws IllegalArgumentException if one of those values is not valid
   */
  public static AcceptanceReport toReport(JsonNode root) {
    List<SeedReport> seeds = new ArrayList<>();
    for (JsonNode seed : root.get("seeds")) {
      seeds.add(toSeed(seed));
    }
    JsonNode aggregate = root.get("aggregate");
    JsonNode hardware = root.get("hardware");
    return AcceptanceReport.builder()
        .schemaVersion(root.get("schema_version").intValue())
        .harnessVersion(root.get("harness_version").textValue())
        .backend(root.get("backend").textValue())
        .adapterHandling(parse(AdapterHandling.class, root.get("adapter_handling")))
        .draftStructure(parse(DraftStructure.class, root.get("draft_structure")))
        .estimator(parse(Estimator.class, root.get("estimator")))
        .draftId(root.get("draft_id").textValue())
        .promptSetSha256(root.get("prompt_set_sha256").textValue())
        .numPrompts(root.get("num_prompts").intValue())
        .decoding(toDecoding(root.get("decoding")))
        .seeds(seeds)
        .aggregate(
            AggregateMetrics.of(
                aggregate.get("alpha_mean").doubleValue(),
                optionalDouble(aggregate.get("alpha_std")),
                aggregate.get("tau_mean").doubleValue(),
                optionalDouble(aggregate.get("tau_std"))))
        .hardware(Hardware.of(hardware.get("gpu").textValue(), hardware.get("count").intValue()))
        .wallClockSeconds(root.get("wall_clock_seconds").doubleValue())
        .build();
  }

  private static SeedReport toSeed(JsonNode seed) {
    List<OptionalDouble> byPosition = new ArrayList<>();
    for (JsonNode v : seed.get("alpha_by_position")) {
      byPosition.add(optionalDouble(v));
    }
    List<PromptCounts> perPrompt = new ArrayList<>();
    for (JsonNode p : seed.get("per_prompt")) {
      perPrompt.add(
          PromptCounts.of(
              p.get("prompt_index").intValue(),
              p.get("steps").longValue(),
              p.get("proposed").longValue(),
              p.get("accepted").longValue()));
    }
    List<PositionCount> positions = new ArrayList<>();
    for (JsonNode p : seed.get("position_counts")) {
      positions.add(
          PositionCount.of(
              p.get("position").intValue(),
              p.get("eligible").longValue(),
              p.get("accepted").longValue()));
    }
    return SeedReport.builder()
        .seed(seed.get("seed").intValue())
        .alpha(seed.get("alpha").doubleValue())
        .tau(seed.get("tau").doubleValue())
        .alphaByPosition(byPosition)
        .totalSteps(seed.get("total_steps").longValue())
        .totalProposed(seed.get("total_proposed").longValue())
        .totalAccepted(seed.get("total_accepted").longValue())
        .excludedPrompts(seed.get("excluded_prompts").intValue())
        .positionCountsExact(seed.get("position_counts_exact").booleanValue())
        .perPrompt(perPrompt)
        .positionCounts(positions)
        .build();
  }

  /** The four decoding keys; anything else is rejected. */
  static Decoding toDecoding(JsonNode decoding) {
    if (!decoding.isObject() || decoding.size() != 4) {
      throw new IllegalArgumentException("decoding must have exactly 4 keys");
    }
    JsonNode temperature = decoding.get("temperature");
    JsonNode maxNew = decoding.get("max_new_tokens");
    JsonNode k = decoding.get("num_speculative_tokens");
    JsonNode dtype = decoding.get("dtype");
    if (temperature == null
        || !temperature.isNumber()
        || maxNew == null
        || !maxNew.canConvertToInt()
        || !maxNew.isIntegralNumber()
        || k == null
        || !k.isIntegralNumber()
        || !k.canConvertToInt()
        || dtype == null
        || !dtype.isTextual()) {
      throw new IllegalArgumentException("decoding keys have the wrong types");
    }
    return Decoding.of(
        temperature.decimalValue(), maxNew.intValue(), k.intValue(), dtype.textValue());
  }

  private static <E extends Enum<E> & WireNamed> E parse(Class<E> type, JsonNode value) {
    return WireNamed.parse(type, value.textValue())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'" + value.textValue() + "' is not one of " + WireNamed.allNames(type)));
  }

  private static OptionalDouble optionalDouble(JsonNode v) {
    return v.isNull() ? OptionalDouble.empty() : OptionalDouble.of(v.doubleValue());
  }

  // --- domain to tree ------------------------------------------------------------------------

  /** The report in the contract's schema, keys in schema order. */
  public static ObjectNode toJson(AcceptanceReport report) {
    ObjectNode root = NODES.objectNode();
    root.put("schema_version", report.schemaVersion());
    root.put("harness_version", report.harnessVersion());
    root.put("backend", report.backend());
    root.put("adapter_handling", report.adapterHandling().wireName());
    root.put("draft_structure", report.draftStructure().wireName());
    root.put("estimator", report.estimator().wireName());
    root.put("draft_id", report.draftId());
    root.put("prompt_set_sha256", report.promptSetSha256());
    root.put("num_prompts", report.numPrompts());
    root.set("decoding", ProbeHasher.decodingJson(report.decoding()));
    ArrayNode seeds = root.putArray("seeds");
    for (SeedReport seed : report.seeds()) {
      seeds.add(seedJson(seed));
    }
    ObjectNode aggregate = root.putObject("aggregate");
    aggregate.put("alpha_mean", report.aggregate().alphaMean());
    putOptional(aggregate, "alpha_std", report.aggregate().alphaStd());
    aggregate.put("tau_mean", report.aggregate().tauMean());
    putOptional(aggregate, "tau_std", report.aggregate().tauStd());
    ObjectNode hardware = root.putObject("hardware");
    hardware.put("gpu", report.hardware().gpu());
    hardware.put("count", report.hardware().count());
    root.put("wall_clock_seconds", report.wallClockSeconds());
    return root;
  }

  private static ObjectNode seedJson(SeedReport seed) {
    ObjectNode node = NODES.objectNode();
    node.put("seed", seed.seed());
    node.put("alpha", seed.alpha());
    node.put("tau", seed.tau());
    ArrayNode byPosition = node.putArray("alpha_by_position");
    for (OptionalDouble v : seed.alphaByPosition()) {
      if (v.isPresent()) {
        byPosition.add(v.getAsDouble());
      } else {
        byPosition.addNull();
      }
    }
    node.put("total_steps", seed.totalSteps());
    node.put("total_proposed", seed.totalProposed());
    node.put("total_accepted", seed.totalAccepted());
    node.put("excluded_prompts", seed.excludedPrompts());
    node.put("position_counts_exact", seed.positionCountsExact());
    ArrayNode perPrompt = node.putArray("per_prompt");
    for (PromptCounts p : seed.perPrompt()) {
      ObjectNode entry = perPrompt.addObject();
      entry.put("prompt_index", p.promptIndex());
      entry.put("steps", p.steps());
      entry.put("proposed", p.proposed());
      entry.put("accepted", p.accepted());
    }
    ArrayNode positions = node.putArray("position_counts");
    for (PositionCount p : seed.positionCounts()) {
      ObjectNode entry = positions.addObject();
      entry.put("position", p.position());
      entry.put("eligible", p.eligible());
      entry.put("accepted", p.accepted());
    }
    return node;
  }

  private static void putOptional(ObjectNode node, String key, OptionalDouble value) {
    if (value.isPresent()) {
      node.put(key, value.getAsDouble());
    } else {
      node.putNull(key);
    }
  }
}
