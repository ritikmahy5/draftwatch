package dev.draftwatch.detect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.AbsoluteDropSpec;
import dev.draftwatch.config.NoiseFloorSpec;
import dev.draftwatch.config.PairedBootstrapSpec;
import dev.draftwatch.config.TrendSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.CountsMeasurements;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Detector rules on invented counts: 10 prompts, 10 steps and 40 proposed each, k = 4. */
public class DetectorTest {
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final CountsMeasurements builder = new CountsMeasurements(4);

  /** A checkpoint at {@code step} whose prompts each accept {@code accepted} of 40. */
  private Measurement at(String job, long step, int accepted) {
    return at(builder, job, step, accepted);
  }

  private static Measurement at(CountsMeasurements b, String job, long step, int accepted) {
    List<Integer> counts = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      counts.add(accepted);
    }
    return b.measurement(
        job, step, T0.plusSeconds(step), CountsMeasurements.prompts(10, 40, counts));
  }

  private static DetectorVerdict absolute(double maxDrop, Measurement current, Measurement base) {
    return new AbsoluteDropDetector(AbsoluteDropSpec.of(Metric.ALPHA, maxDrop))
        .evaluate(current, Optional.of(base), List.of(base, current));
  }

  // --- threshold detectors -------------------------------------------------------------------

  @Test
  public void absoluteDropComparesSeedMeans() {
    Measurement base = at("b", 100, 28); // alpha 0.7
    Measurement current = at("c", 200, 24); // alpha 0.6
    DetectorVerdict v = absolute(0.05, current, base);
    assertEquals(DetectorVerdict.Kind.REGRESSION, v.kind());
    assertEquals(240.0 / 400.0 - 280.0 / 400.0, v.observed().getAsDouble(), 0.0);
    assertEquals(-0.05, v.threshold().getAsDouble(), 0.0);
    assertEquals(Optional.of("b"), v.baselineJobId());
    assertEquals(DetectorVerdict.Kind.OK, absolute(0.2, current, base).kind());
  }

  @Test
  public void anImprovementIsNeverARegression() {
    DetectorVerdict v = absolute(0.0, at("c", 200, 30), at("b", 100, 28));
    assertEquals(DetectorVerdict.Kind.OK, v.kind());
  }

  @Test
  public void noiseFloorUsesKTimesRootTwoTimesSigma() {
    NoiseFloorDetector d = new NoiseFloorDetector(NoiseFloorSpec.of(Metric.ALPHA, 2, 0.02));
    assertEquals(2 * Math.sqrt(2) * 0.02, d.floor(), 0.0);
    Measurement base = at("b", 100, 28);
    assertEquals(
        DetectorVerdict.Kind.OK,
        d.evaluate(at("c", 200, 26), Optional.of(base), List.of()).kind()); // drop 0.05 < 0.0566
    assertEquals(
        DetectorVerdict.Kind.REGRESSION,
        d.evaluate(at("c", 200, 25), Optional.of(base), List.of()).kind()); // drop 0.075
  }

  // --- the guard and missing baselines ------------------------------------------------------

  @Test
  public void incomparablePairIsAnErrorNamingTheField() {
    Measurement base = at("b", 100, 28);
    Measurement newHarness =
        at(new CountsMeasurements(4).harnessVersion("fake-0.2.0"), "c", 200, 20);
    DetectorVerdict v = absolute(0.05, newHarness, base);
    assertEquals(DetectorVerdict.Kind.ERROR, v.kind());
    assertEquals("incomparable: harness_version", v.explanation());
    Measurement otherBackend = at(new CountsMeasurements(4).backend("vllm==0.9"), "c", 200, 20);
    assertEquals("incomparable: backend", absolute(0.05, otherBackend, base).explanation());
  }

  @Test
  public void comparabilityChecksFieldsInContractOrder() {
    Measurement base = at("b", 100, 28);
    CountsMeasurements differing =
        new CountsMeasurements(4).harnessVersion("x").backend("y").probeHash("1".repeat(64));
    Measurement both = at(differing, "c", 200, 28);
    assertEquals(Optional.of("probe_hash"), Comparability.mismatch(both, base));
    assertEquals(Optional.empty(), Comparability.mismatch(at("c", 200, 20), base));
  }

  @Test
  public void firstMeasurementOfTheBaselineIsInsufficientData() {
    DetectorVerdict v =
        new AbsoluteDropDetector(AbsoluteDropSpec.of(Metric.ALPHA, 0.05))
            .evaluate(at("b", 100, 28), Optional.empty(), List.of());
    assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, v.kind());
  }

  // --- trend -------------------------------------------------------------------------------

  private static TrendDetector trend(int window, double maxSlope) {
    return new TrendDetector(TrendSpec.of(Metric.ALPHA, window, maxSlope));
  }

  @Test
  public void trendNeedsAFullWindow() {
    List<Measurement> history = List.of(at("a", 100, 28), at("b", 200, 26));
    DetectorVerdict v = trend(3, -0.01).evaluate(history.get(1), Optional.empty(), history);
    assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, v.kind());
    assertEquals("only 2 of 3 comparable checkpoints up to step 200", v.explanation());
  }

  @Test
  public void trendUsesTheLastWindowUpToTheCurrentStep() {
    // alpha by step: 100 -> 0.5, 200 -> 0.7, 300 -> 0.65, 400 -> 0.6, 500 (later step) -> 0.1
    List<Measurement> history =
        List.of(
            at("a", 100, 20),
            at("b", 200, 28),
            at("c", 300, 26),
            at("d", 400, 24),
            at("e", 500, 4));
    DetectorVerdict v = trend(3, -0.01).evaluate(history.get(3), Optional.empty(), history);
    assertEquals(DetectorVerdict.Kind.REGRESSION, v.kind());
    assertEquals(-0.05, v.observed().getAsDouble(), 1e-12); // over 0.7, 0.65, 0.6
    assertTrue(v.explanation(), v.explanation().startsWith("slope of alpha over steps 200..400"));
  }

  @Test
  public void trendUsesOneMeasurementPerCheckpointAndSkipsIncomparableOnes() {
    Measurement oldRun = at("a1", 100, 10); // re-measured below: the later one counts
    Measurement newRun = at("a2", 100, 28);
    Measurement incomparable = at(new CountsMeasurements(4).backend("other"), "x", 150, 0);
    Measurement b = at("b", 200, 28);
    Measurement c = at("c", 300, 28);
    List<Measurement> history = List.of(oldRun, newRun, incomparable, b, c);
    DetectorVerdict v = trend(3, -0.01).evaluate(c, Optional.empty(), history);
    assertEquals(DetectorVerdict.Kind.OK, v.kind());
    assertEquals(0.0, v.observed().getAsDouble(), 0.0);
    assertTrue(v.explanation(), v.explanation().endsWith("; 1 incomparable excluded"));
  }

  // --- bootstrap and suite -------------------------------------------------------------------

  @Test
  public void bootstrapReportsItsInterval() {
    PairedBootstrapDetector d =
        new PairedBootstrapDetector(
            PairedBootstrapSpec.withDefaults(Metric.ALPHA), new MetricCalculator());
    Measurement base = at("b", 100, 28);
    DetectorVerdict v = d.evaluate(at("c", 200, 24), Optional.of(base), List.of());
    assertEquals(DetectorVerdict.Kind.REGRESSION, v.kind());
    assertEquals(-0.1, v.intervalUpper().getAsDouble(), 1e-12); // every prompt drops by 0.1
    assertEquals(-0.0, v.threshold().getAsDouble(), 0.0);
    assertTrue(v.explanation(), v.explanation().startsWith("95% interval of (current - baseline)"));
  }

  @Test
  public void bootstrapResampleWithNothingProposedIsAnErrorNotADroppedDraw() {
    // Prompt 1 proposes nothing, so a resample drawing only prompt 1 has an undefined alpha.
    List<PromptCounts> prompts =
        List.of(PromptCounts.of(0, 10, 40, 28), PromptCounts.of(1, 3, 0, 0));
    Measurement base = builder.measurement("b", 100, T0, prompts);
    Measurement current = builder.measurement("c", 200, T0.plusSeconds(1), prompts);
    DetectorVerdict v =
        new PairedBootstrapDetector(
                PairedBootstrapSpec.withDefaults(Metric.ALPHA), new MetricCalculator())
            .evaluate(current, Optional.of(base), List.of());
    assertEquals(DetectorVerdict.Kind.ERROR, v.kind());
    assertTrue(v.explanation(), v.explanation().contains("undefined on bootstrap resample"));
  }

  @Test
  public void suiteTurnsAThrowingDetectorIntoAnErrorAndKeepsGoing() {
    RegressionDetector broken =
        new RegressionDetector() {
          @Override
          public String describe() {
            return "broken";
          }

          @Override
          public Metric metric() {
            return Metric.ALPHA;
          }

          @Override
          public DetectorVerdict evaluate(
              Measurement current, Optional<Measurement> baseline, List<Measurement> history) {
            throw new IllegalStateException("boom");
          }
        };
    DetectorSuite suite =
        new DetectorSuite(
            List.of(broken, new AbsoluteDropDetector(AbsoluteDropSpec.of(Metric.ALPHA, 0.05))));
    Measurement base = at("b", 100, 28);
    List<DetectorVerdict> verdicts = suite.run(at("c", 200, 28), Optional.of(base), List.of());
    assertEquals(DetectorVerdict.Kind.ERROR, verdicts.get(0).kind());
    assertEquals("detector failed: IllegalStateException: boom", verdicts.get(0).explanation());
    assertEquals(DetectorVerdict.Kind.OK, verdicts.get(1).kind());
  }

  @Test
  public void incomparablePairDoesNotThrowOutOfTheSuite() {
    DetectorSuite suite =
        new DetectorSuite(
            List.of(
                new PairedBootstrapDetector(
                    PairedBootstrapSpec.withDefaults(Metric.ALPHA), new MetricCalculator()),
                new AbsoluteDropDetector(AbsoluteDropSpec.of(Metric.ALPHA, 0.05)),
                new NoiseFloorDetector(NoiseFloorSpec.of(Metric.ALPHA, 2, 0.01))));
    Measurement base = at("b", 100, 28);
    Measurement other = at(new CountsMeasurements(4).backend("other"), "c", 200, 20);
    for (DetectorVerdict v : suite.run(other, Optional.of(base), List.of(base, other))) {
      assertEquals(DetectorVerdict.Kind.ERROR, v.kind());
      assertEquals("incomparable: backend", v.explanation());
    }
  }
}
