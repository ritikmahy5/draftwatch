package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.detect.Comparability;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.detect.PairedBootstrapDetector;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.CountsMeasurements;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

/** Subset measurements built from invented per-prompt counts. */
public class SubsetMeasurementsTest {
  private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");
  private final MetricCalculator calc = new MetricCalculator();
  private final SubsetMeasurements subsets = new SubsetMeasurements(calc);
  private final CountsMeasurements counts = new CountsMeasurements(4);

  private static List<PromptCounts> prompts(int n, int shift) {
    List<PromptCounts> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      long steps = 5 + i % 7;
      long accepted = (steps * 4 * (40 + (i * 13) % 45)) / 100 - shift;
      out.add(PromptCounts.of(i, steps, steps * 4, accepted));
    }
    return out;
  }

  private Measurement full(String job, long step, int shift) {
    return counts.measurement(job, step, T0.plusSeconds(step), prompts(40, shift));
  }

  @Test
  public void subsetTotalsAndAggregate() {
    Measurement full = full("j-full", 100, 0);
    Subset s = SubsetDraw.draw(40, 10, 1, 10).get(0);
    Measurement sub = subsets.of(full, s);
    List<PromptCounts> chosen = new ArrayList<>();
    for (int i : s.indices()) {
      chosen.add(full.report().seeds().get(0).perPrompt().get(i));
    }
    SeedReport seed = sub.report().seeds().get(0);
    assertEquals(chosen, seed.perPrompt());
    long steps = 0;
    long proposed = 0;
    long accepted = 0;
    for (PromptCounts p : chosen) {
      steps += p.steps();
      proposed += p.proposed();
      accepted += p.accepted();
    }
    assertEquals(steps, seed.totalSteps());
    assertEquals(proposed, seed.totalProposed());
    assertEquals(accepted, seed.totalAccepted());
    double alpha = calc.alpha(Estimator.TOKEN_WEIGHTED, chosen).getAsDouble();
    assertEquals(alpha, seed.alpha(), 0.0);
    assertEquals(alpha, sub.report().aggregate().alphaMean(), 0.0);
    assertEquals(
        calc.tau(Estimator.TOKEN_WEIGHTED, chosen).getAsDouble(),
        sub.report().aggregate().tauMean(),
        0.0);
    assertEquals(10, sub.report().numPrompts());
    assertTrue(seed.positionCounts().isEmpty());
    assertTrue(seed.alphaByPosition().isEmpty());
    assertFalse(seed.positionCountsExact());
    assertEquals(0, seed.excludedPrompts());
    assertEquals(full.fingerprint(), sub.fingerprint());
    assertEquals(full.provenance().checkpoint(), sub.provenance().checkpoint());
    assertEquals(full.report().wallClockSeconds(), sub.report().wallClockSeconds(), 0.0);
  }

  @Test
  public void subsetIdentityFollowsTheRules() {
    Measurement full = full("j-full", 100, 0);
    Subset s = SubsetDraw.draw(40, 10, 3, 10).get(2);
    Measurement sub = subsets.of(full, s);
    assertEquals(
        SubsetMeasurements.sha256Hex("subset-probe:" + full.probeHash() + ":10:2"),
        sub.probeHash());
    assertEquals(
        SubsetMeasurements.sha256Hex("subset-prompts:" + "f".repeat(64) + ":10:2"),
        sub.report().promptSetSha256());
    assertEquals(sub.report().promptSetSha256(), sub.provenance().promptSetSha256());
    assertEquals("j-full-n10-s2", sub.jobId());
    // The literal digest of a known input pins the hash function itself.
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        SubsetMeasurements.sha256Hex("abc"));
  }

  @Test
  public void sameSubsetComparableAcrossCheckpoints() {
    List<Subset> s = SubsetDraw.draw(40, 10, 2, 10);
    Measurement a = full("j-a", 100, 0);
    Measurement b = full("j-b", 200, 1);
    assertEquals(
        Optional.empty(),
        Comparability.mismatch(subsets.of(a, s.get(0)), subsets.of(b, s.get(0))));
    assertEquals(
        Optional.of("probe_hash"),
        Comparability.mismatch(subsets.of(a, s.get(0)), subsets.of(b, s.get(1))));
  }

  @Test
  public void uniqueJobIds() {
    Measurement a = full("j-a", 100, 0);
    Set<String> ids = new HashSet<>();
    for (int n : new int[] {10, 20}) {
      for (Subset s : SubsetDraw.draw(40, n, 30, n)) {
        assertTrue(ids.add(subsets.of(a, s).jobId()));
      }
    }
    assertNotEquals(subsets.of(a, SubsetDraw.draw(40, 10, 1, 10).get(0)).jobId(), a.jobId());
  }

  @Test
  public void identitySubsetReproducesFullBootstrap() {
    Measurement base = full("j-base", 0, 0);
    Measurement cur = full("j-cur", 100, 1);
    Subset all = SubsetDraw.draw(40, 40, 1, 40).get(0);
    PairedBootstrapDetector b0 =
        new PairedBootstrapDetector(PairedBootstrapSpec.withDefaults(Metric.ALPHA), calc);
    DetectorVerdict onFull = b0.evaluate(cur, Optional.of(base), List.of());
    DetectorVerdict onSubset =
        b0.evaluate(subsets.of(cur, all), Optional.of(subsets.of(base, all)), List.of());
    assertEquals(onFull.observed(), onSubset.observed());
    assertEquals(onFull.intervalLower(), onSubset.intervalLower());
    assertEquals(onFull.intervalUpper(), onSubset.intervalUpper());
    assertEquals(onFull.kind(), onSubset.kind());
  }
}
