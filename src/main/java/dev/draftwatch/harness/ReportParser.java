package dev.draftwatch.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.draftwatch.config.CanonicalJson;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.AggregateMetrics;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.stats.MetricCalculator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Validates a harness report against every rule of MEASUREMENT_CONTRACT.md, in the contract's
 * order, and returns it unchanged as an {@link AcceptanceReport}. The first violation is thrown
 * as a {@link ReportViolationException} naming its rule; nothing is ever repaired.
 */
public final class ReportParser {
  /** Absolute tolerance for comparing reported and recomputed real numbers. */
  public static final double TOLERANCE = 1e-9;

  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final MetricCalculator calculator;

  public ReportParser(MetricCalculator calculator) {
    this.calculator = Objects.requireNonNull(calculator, "calculator");
  }

  /**
   * Reads and validates the report at {@code file}.
   *
   * @throws ReportViolationException for the first rule the report breaks
   */
  public AcceptanceReport parse(Path file, ExpectedReport expected) {
    JsonNode root = read(file);
    ReportJson.checkShape(root);
    identity(root, expected);
    AcceptanceReport report;
    try {
      report = ReportJson.toReport(root);
    } catch (IllegalArgumentException e) {
      // identity() accepted every value toReport() parses, so this is a parser bug.
      throw new IllegalStateException("validated report failed to convert: " + e.getMessage(), e);
    }
    int k = expected.decoding().numSpeculativeTokens();
    for (int i = 0; i < report.seeds().size(); i++) {
      seed(report.seeds().get(i), "seeds[" + i + "]", report, k);
    }
    aggregate(report);
    return report;
  }

  private JsonNode read(Path file) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      throw new ReportViolationException(ReportRule.REPORT_MISSING, "no report at " + file);
    } catch (IOException e) {
      throw new ReportViolationException(
          ReportRule.REPORT_MISSING, "cannot read " + file + ": " + e.getMessage(), e);
    }
    try {
      JsonNode root = json.readTree(bytes);
      if (root == null || root.isMissingNode()) {
        throw new ReportViolationException(ReportRule.JSON, file + " is empty");
      }
      return root;
    } catch (JsonProcessingException e) {
      throw new ReportViolationException(
          ReportRule.JSON, file + " is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new ReportViolationException(ReportRule.JSON, "cannot parse " + file, e);
    }
  }

  // --- rules 4-12: identity and comparability ---------------------------------------------

  private static void identity(JsonNode root, ExpectedReport expected) {
    int version = root.get("schema_version").intValue();
    require(
        version == ExpectedReport.SCHEMA_VERSION,
        ReportRule.SCHEMA_VERSION,
        "schema_version is " + version + ", this parser supports " + ExpectedReport.SCHEMA_VERSION);
    String structure = root.get("draft_structure").textValue();
    require(
        DraftStructure.CHAIN.wireName().equals(structure),
        ReportRule.DRAFT_STRUCTURE,
        "draft_structure is '" + structure + "', only 'chain' is supported");
    same(ReportRule.ESTIMATOR, expected.estimator().wireName(), root.get("estimator").textValue());
    same(ReportRule.DRAFT_ID, expected.draftId(), root.get("draft_id").textValue());
    same(
        ReportRule.DECODING,
        CanonicalJson.write(ProbeHasher.decodingJson(expected.decoding())),
        CanonicalJson.write(root.get("decoding")));
    same(
        ReportRule.PROMPT_SET_SHA256,
        expected.promptSet().sha256(),
        root.get("prompt_set_sha256").textValue());
    int numPrompts = root.get("num_prompts").intValue();
    require(
        numPrompts == expected.promptSet().promptCount(),
        ReportRule.NUM_PROMPTS,
        "num_prompts is "
            + numPrompts
            + ", the prompt file has "
            + expected.promptSet().promptCount());
    List<Integer> seeds = new ArrayList<>();
    for (JsonNode seed : root.get("seeds")) {
      seeds.add(seed.get("seed").intValue());
    }
    same(ReportRule.SEEDS, expected.seeds(), seeds);
    same(
        ReportRule.ADAPTER_HANDLING,
        expected.adapterHandling().wireName(),
        root.get("adapter_handling").textValue());
  }

  // --- rules 13-27: per seed --------------------------------------------------------------

  private void seed(SeedReport seed, String at, AcceptanceReport report, int k) {
    List<PromptCounts> prompts = seed.perPrompt();
    require(
        prompts.size() == report.numPrompts(),
        ReportRule.PER_PROMPT_LENGTH,
        at + " has " + prompts.size() + " per_prompt entries, num_prompts is "
            + report.numPrompts());
    for (int i = 0; i < prompts.size(); i++) {
      require(
          prompts.get(i).promptIndex() == i,
          ReportRule.PROMPT_INDICES,
          at + ".per_prompt[" + i + "] has prompt_index " + prompts.get(i).promptIndex());
    }
    long steps = 0;
    long proposed = 0;
    long accepted = 0;
    boolean exact = true;
    for (int i = 0; i < prompts.size(); i++) {
      PromptCounts p = prompts.get(i);
      long maxProposed = multiply(p.steps(), k);
      require(
          0 <= p.accepted() && p.accepted() <= p.proposed() && p.proposed() <= maxProposed,
          ReportRule.PROMPT_COUNTS,
          at + ".per_prompt[" + i + "] has steps " + p.steps() + ", proposed " + p.proposed()
              + ", accepted " + p.accepted() + ", breaking 0 <= accepted <= proposed <= steps * "
              + k);
      exact &= p.proposed() == maxProposed;
      steps += p.steps();
      proposed += p.proposed();
      accepted += p.accepted();
    }
    require(
        seed.totalSteps() == steps
            && seed.totalProposed() == proposed
            && seed.totalAccepted() == accepted,
        ReportRule.TOTALS,
        at + " totals (steps, proposed, accepted) are (" + seed.totalSteps() + ", "
            + seed.totalProposed() + ", " + seed.totalAccepted() + "), per_prompt sums to ("
            + steps + ", " + proposed + ", " + accepted + ")");
    positions(seed, at, k);
    require(
        seed.positionCountsExact() == exact,
        ReportRule.POSITION_COUNTS_EXACT,
        at + ".position_counts_exact is " + seed.positionCountsExact() + ", per_prompt implies "
            + exact);
    Estimator estimator = report.estimator();
    int excluded = calculator.excludedPrompts(estimator, prompts);
    require(
        seed.excludedPrompts() == excluded,
        ReportRule.EXCLUDED_PROMPTS,
        at + ".excluded_prompts is " + seed.excludedPrompts() + ", " + estimator.wireName()
            + " excludes " + excluded);
    require(
        seed.alpha() >= 0 && seed.alpha() <= 1,
        ReportRule.ALPHA_RANGE,
        at + ".alpha " + seed.alpha() + " is outside [0, 1]");
    require(
        seed.tau() >= 1 && seed.tau() <= k + 1,
        ReportRule.TAU_RANGE,
        at + ".tau " + seed.tau() + " is outside [1, " + (k + 1) + "]");
    recomputed(ReportRule.ALPHA, at + ".alpha", seed.alpha(), calculator.alpha(estimator, prompts));
    recomputed(ReportRule.TAU, at + ".tau", seed.tau(), calculator.tau(estimator, prompts));
  }

  private void positions(SeedReport seed, String at, int k) {
    List<PositionCount> positions = seed.positionCounts();
    require(
        positions.size() == k && seed.alphaByPosition().size() == k,
        ReportRule.POSITION_LENGTHS,
        at + " has " + seed.alphaByPosition().size() + " alpha_by_position and "
            + positions.size() + " position_counts entries, num_speculative_tokens is " + k);
    long acceptedSum = 0;
    for (int j = 0; j < k; j++) {
      PositionCount p = positions.get(j);
      require(
          p.position() == j + 1 && 0 <= p.accepted() && p.accepted() <= p.eligible(),
          ReportRule.POSITION_COUNTS,
          at + ".position_counts[" + j + "] has position " + p.position() + ", eligible "
              + p.eligible() + ", accepted " + p.accepted() + "; it must have position " + (j + 1)
              + " and 0 <= accepted <= eligible");
      acceptedSum += p.accepted();
    }
    require(
        positions.get(0).eligible() <= seed.totalSteps(),
        ReportRule.POSITION_COUNTS,
        at + " eligible at position 1 is " + positions.get(0).eligible() + ", more than "
            + seed.totalSteps() + " steps");
    for (int j = 0; j + 1 < k; j++) {
      require(
          positions.get(j + 1).eligible() <= positions.get(j).accepted(),
          ReportRule.POSITION_MONOTONE,
          at + " eligible at position " + (j + 2) + " (" + positions.get(j + 1).eligible()
              + ") exceeds accepted at position " + (j + 1) + " ("
              + positions.get(j).accepted() + ")");
    }
    require(
        acceptedSum == seed.totalAccepted(),
        ReportRule.POSITION_TOTALS,
        at + " position_counts accept " + acceptedSum + " tokens, total_accepted is "
            + seed.totalAccepted());
    List<OptionalDouble> expected = calculator.alphaByPosition(positions);
    for (int j = 0; j < k; j++) {
      OptionalDouble reported = seed.alphaByPosition().get(j);
      OptionalDouble computed = expected.get(j);
      boolean ok =
          reported.isPresent() == computed.isPresent()
              && (computed.isEmpty() || close(reported.getAsDouble(), computed.getAsDouble()));
      require(
          ok,
          ReportRule.ALPHA_BY_POSITION,
          at + ".alpha_by_position[" + j + "] is " + text(reported) + ", accepted / eligible is "
              + text(computed));
    }
  }

  // --- rules 28-29: aggregate -------------------------------------------------------------

  private void aggregate(AcceptanceReport report) {
    List<Double> alphas = new ArrayList<>();
    List<Double> taus = new ArrayList<>();
    for (SeedReport seed : report.seeds()) {
      alphas.add(seed.alpha());
      taus.add(seed.tau());
    }
    AggregateMetrics a = report.aggregate();
    double alphaMean = calculator.mean(alphas);
    double tauMean = calculator.mean(taus);
    require(
        close(a.alphaMean(), alphaMean) && close(a.tauMean(), tauMean),
        ReportRule.AGGREGATE_MEAN,
        "aggregate means are (" + a.alphaMean() + ", " + a.tauMean() + "), seed means are ("
            + alphaMean + ", " + tauMean + ")");
    std(a.alphaStd(), calculator.sampleStd(alphas), "alpha_std");
    std(a.tauStd(), calculator.sampleStd(taus), "tau_std");
  }

  private static void std(OptionalDouble reported, OptionalDouble computed, String name) {
    boolean ok =
        reported.isPresent() == computed.isPresent()
            && (computed.isEmpty() || close(reported.getAsDouble(), computed.getAsDouble()));
    require(
        ok,
        ReportRule.AGGREGATE_STD,
        "aggregate." + name + " is " + text(reported) + ", the seeds' sample standard deviation is "
            + text(computed) + " (null with one seed)");
  }

  // --- helpers ------------------------------------------------------------------------------

  private static void recomputed(
      ReportRule rule, String field, double reported, OptionalDouble computed) {
    require(
        computed.isPresent(),
        rule,
        field + " is undefined: there is nothing to average under the declared estimator");
    require(
        close(reported, computed.getAsDouble()),
        rule,
        field + " is " + reported + ", recomputed from per_prompt it is " + computed.getAsDouble());
  }

  static boolean close(double a, double b) {
    return Math.abs(a - b) <= TOLERANCE;
  }

  private static long multiply(long a, long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  private static String text(OptionalDouble v) {
    return v.isPresent() ? Double.toString(v.getAsDouble()) : "null";
  }

  private static void same(ReportRule rule, Object expected, Object actual) {
    require(
        expected.equals(actual),
        rule,
        rule.wireName() + " is " + actual + ", the engine passed " + expected);
  }

  private static void require(boolean ok, ReportRule rule, String detail) {
    if (!ok) {
      throw new ReportViolationException(rule, detail);
    }
  }
}
