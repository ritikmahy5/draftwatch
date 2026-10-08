package dev.draftwatch.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Test;

public class DomainInvariantsTest {
  private static final Path DIR = Paths.get("/ckpt");

  private static void assertRejected(Runnable action, String expectedFragment) {
    try {
      action.run();
      fail("expected rejection containing: " + expectedFragment);
    } catch (IllegalArgumentException | NullPointerException e) {
      assertTrue(
          "message '" + e.getMessage() + "' lacks '" + expectedFragment + "'",
          e.getMessage().contains(expectedFragment));
    }
  }

  // --- WireNamed --------------------------------------------------------------------------------

  @Test
  public void wireNamesParseExactlyAndCaseSensitively() {
    assertEquals(
        Optional.of(Estimator.SIMPLE_MEAN), WireNamed.parse(Estimator.class, "simple_mean"));
    assertEquals(Optional.empty(), WireNamed.parse(Estimator.class, "SIMPLE_MEAN"));
    assertEquals(Optional.empty(), WireNamed.parse(Estimator.class, "simple-mean"));
    assertEquals("token_weighted | simple_mean", WireNamed.allNames(Estimator.class));
    assertEquals("full | adapter", WireNamed.allNames(CheckpointType.class));
    assertEquals("chain", WireNamed.allNames(DraftStructure.class));
    assertEquals("none | merged", WireNamed.allNames(AdapterHandling.class));
  }

  // --- Names ------------------------------------------------------------------------------------

  @Test
  public void namesAllowPathSafeIdentifiersOnly() {
    for (String ok : Arrays.asList("a", "run-1", "chat.default", "A_b-9")) {
      assertTrue(ok, Names.isValid(ok));
    }
    for (String bad : Arrays.asList("", "-run", ".hidden", "a/b", "a b", "..", "a\\b", "é")) {
      assertFalse(bad, Names.isValid(bad));
    }
    assertFalse(Names.isValid(null));
  }

  // --- Decoding ---------------------------------------------------------------------------------

  @Test
  public void decodingTreatsZeroAndZeroPointZeroAsEqual() {
    Decoding a = Decoding.of(new BigDecimal("0"), 8, 3, "bfloat16");
    Decoding b = Decoding.of(new BigDecimal("0.0"), 8, 3, "bfloat16");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertTrue(a.isGreedy());
    assertEquals("0", b.temperature().toPlainString());
  }

  @Test
  public void decodingStripsTrailingZerosFromTemperature() {
    Decoding d = Decoding.of(new BigDecimal("0.70"), 8, 3, "bfloat16");
    assertEquals(Decoding.of(new BigDecimal("0.7"), 8, 3, "bfloat16"), d);
    assertFalse(d.isGreedy());
  }

  @Test
  public void decodingRejectsInvalidValues() {
    assertRejected(() -> Decoding.of(new BigDecimal("-0.1"), 8, 3, "x"), "temperature");
    assertRejected(() -> Decoding.of(BigDecimal.ONE, 0, 3, "x"), "max_new_tokens");
    assertRejected(() -> Decoding.of(BigDecimal.ONE, 8, 0, "x"), "num_speculative_tokens");
    assertRejected(() -> Decoding.of(BigDecimal.ONE, 8, 3, " "), "dtype");
  }

  // --- Probe ------------------------------------------------------------------------------------

  private static Probe probe(List<Integer> seeds) {
    return Probe.of(
        "p",
        Draft.of("d", DIR, DraftStructure.CHAIN),
        Paths.get("/p.jsonl"),
        SyntheticDomain.decoding(),
        Estimator.TOKEN_WEIGHTED,
        seeds);
  }

  @Test
  public void probeRejectsEmptyDuplicateOrNegativeSeeds() {
    assertRejected(() -> probe(List.of()), "seeds must not be empty");
    assertRejected(() -> probe(List.of(1, 2, 1)), "1 repeats");
    assertRejected(() -> probe(List.of(-1)), "seed must be >= 0");
  }

  @Test
  public void probeCopiesSeedsDefensively() {
    List<Integer> seeds = new ArrayList<>(List.of(3, 1));
    Probe p = probe(seeds);
    seeds.add(7);
    assertEquals(List.of(3, 1), p.seeds());
    try {
      p.seeds().add(9);
      fail("seeds must be unmodifiable");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  @Test
  public void probeRejectsInvalidId() {
    assertRejected(
        () ->
            Probe.of(
                "bad id",
                Draft.of("d", DIR, DraftStructure.CHAIN),
                Paths.get("/p.jsonl"),
                SyntheticDomain.decoding(),
                Estimator.TOKEN_WEIGHTED,
                List.of(0)),
        "probe id 'bad id'");
  }

  // --- ResolvedProbe ----------------------------------------------------------------------------

  @Test
  public void resolvedProbeRejectsPromptSetForAnotherFile() {
    Probe p = probe(List.of(0));
    PromptSet other = PromptSet.of(Paths.get("/other.jsonl"), SyntheticDomain.SHA_A, 1);
    assertRejected(
        () -> ResolvedProbe.of(p, "sampled-x", other, SyntheticDomain.SHA_B),
        "is not the probe's file");
  }

  // --- Target -----------------------------------------------------------------------------------

  private static Target.Builder target(CheckpointType type) {
    return Target.builder().name("run").checkpointDirs(List.of(DIR)).checkpointType(type);
  }

  @Test
  public void targetAppliesSpecDefaults() {
    Target t = target(CheckpointType.FULL).build();
    assertEquals("checkpoint-(\\d+)$", t.stepRegex().pattern());
    assertEquals("FINAL", t.finalMarker());
    assertEquals(Optional.empty(), t.baseModel());
  }

  @Test
  public void adapterTargetRequiresBaseModel() {
    assertRejected(() -> target(CheckpointType.ADAPTER).build(), "base_model is required");
    Target t = target(CheckpointType.ADAPTER).baseModel(Paths.get("/base")).build();
    assertEquals(Optional.of(Paths.get("/base")), t.baseModel());
  }

  @Test
  public void fullTargetRejectsBaseModel() {
    assertRejected(
        () -> target(CheckpointType.FULL).baseModel(Paths.get("/base")).build(),
        "base_model is only valid");
  }

  @Test
  public void targetRejectsStepRegexWithoutGroupAndEmptyDirs() {
    assertRejected(
        () -> target(CheckpointType.FULL).stepRegex(Pattern.compile("checkpoint-\\d+")).build(),
        "capture group");
    assertRejected(
        () -> target(CheckpointType.FULL).checkpointDirs(List.of()).build(),
        "checkpoint_dirs must not be empty");
  }

  @Test
  public void targetEqualityUsesRegexText() {
    assertEquals(target(CheckpointType.FULL).build(), target(CheckpointType.FULL).build());
    assertNotEquals(
        target(CheckpointType.FULL).build(),
        target(CheckpointType.FULL).stepRegex(Pattern.compile("step(\\d+)")).build());
  }

  // --- Checkpoint -------------------------------------------------------------------------------

  @Test
  public void checkpointRequiresStepAndMatchingBaseModel() {
    Checkpoint.Builder noStep =
        Checkpoint.builder()
            .targetName("run")
            .path(DIR)
            .fingerprint("sampled-x")
            .type(CheckpointType.FULL);
    assertRejected(noStep::build, "step must be >= 0");
    assertRejected(
        () -> noStep.step(1).type(CheckpointType.ADAPTER).build(), "base model is required");
  }

  // --- Provenance and Measurement ---------------------------------------------------------------

  @Test
  public void provenanceRejectsEndBeforeStart() {
    assertRejected(
        () ->
            SyntheticDomain.provenance()
                .endTime(Instant.parse("2025-12-31T23:59:59Z"))
                .build(),
        "precedes start_time");
  }

  @Test
  public void provenanceRejectsMissingField() {
    assertRejected(() -> SyntheticDomain.provenance().jobId(null).build(), "job_id");
    assertRejected(() -> SyntheticDomain.provenance().attempt(0).build(), "attempt");
  }

  @Test
  public void measurementAcceptsConsistentProvenanceAndReport() {
    Measurement m =
        Measurement.of(SyntheticDomain.provenance().build(), SyntheticDomain.report().build());
    assertEquals("sampled-" + SyntheticDomain.SHA_A, m.fingerprint());
    assertEquals(SyntheticDomain.SHA_A, m.probeHash());
    assertEquals("job-1", m.jobId());
  }

  @Test
  public void measurementRejectsProvenanceThatDescribesAnotherRun() {
    AcceptanceReport report = SyntheticDomain.report().build();
    assertRejected(
        () -> Measurement.of(SyntheticDomain.provenance().backend("other").build(), report),
        "backend differs");
    assertRejected(
        () -> Measurement.of(SyntheticDomain.provenance().seeds(List.of(5)).build(), report),
        "seeds differs");
    assertRejected(
        () -> Measurement.of(SyntheticDomain.provenance().dtype("float16").build(), report),
        "dtype differs");
    assertRejected(
        () ->
            Measurement.of(
                SyntheticDomain.provenance().estimator(Estimator.SIMPLE_MEAN).build(), report),
        "estimator differs");
  }

  @Test
  public void reportRequiresAtLeastOneSeed() {
    assertRejected(
        () -> SyntheticDomain.report().seeds(List.of()).build(), "seeds must not be empty");
  }
}
