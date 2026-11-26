package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.detect.RegressionDetector;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/** Replaying subsets through the detectors, on synthetic data. */
public class ReplayerTest {
  private static final int POOL = 20;
  private final MetricCalculator calc = new MetricCalculator();
  private final Replayer replayer = new Replayer(new SubsetMeasurements(calc));
  private final List<Subset> subsets = SubsetDraw.draw(POOL, 10, 4, 10);

  private EvaluationData data(ScoringFixtures.Shift shift, Set<String> skip) {
    InMemoryResultRepository results = new InMemoryResultRepository();
    InMemoryBaselineRepository baselines = new InMemoryBaselineRepository();
    ScoringFixtures.family(results, baselines, "qwen", POOL, shift, skip);
    return EvaluationData.load(results, baselines);
  }

  private static DetectorConfig config(String id) {
    return DetectorConfigs.all(new MetricCalculator()).stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .get();
  }

  private FlagMatrix replay(EvaluationData d, Run run, String id) {
    return replayer.replay(
        d.baseline("qwen", ProbeKind.CHAT),
        d.series("qwen", ProbeKind.CHAT, run),
        run,
        subsets,
        config(id));
  }

  @Test
  public void configsMatchTheLiveLog() {
    List<DetectorConfig> all = DetectorConfigs.all(calc);
    assertEquals(
        List.of("B0", "B1", "A", "T"),
        all.stream().map(DetectorConfig::id).collect(Collectors.toList()));
    assertEquals(
        "paired_bootstrap(metric=alpha, confidence=0.95, min_effect=0, resamples=2000,"
            + " bootstrap_seed=0)",
        all.get(0).detector().describe());
    assertEquals(
        "paired_bootstrap(metric=alpha, confidence=0.95, min_effect=0.01, resamples=2000,"
            + " bootstrap_seed=0)",
        all.get(1).detector().describe());
    assertEquals("absolute_drop(metric=alpha, max_drop=0.02)", all.get(2).detector().describe());
    assertEquals(
        "trend(metric=alpha, window=4, max_slope=-0.005)", all.get(3).detector().describe());
  }

  @Test
  public void comparedStepsExcludeCheckpointZero() {
    EvaluationData d = data((r, k, s) -> 0, Set.of());
    assertEquals(ExpectedSteps.RUN1.subList(1, 13), replay(d, Run.RUN1, "A").steps());
    assertEquals(ExpectedSteps.RUN2, replay(d, Run.RUN2, "A").steps());
    assertEquals(4, replay(d, Run.RUN2, "A").subsets());
  }

  private static int declining(Run run, ProbeKind kind, long step) {
    return run == Run.RUN1 ? (int) (step / 100) : ExpectedSteps.RUN2.indexOf(step) + 1;
  }

  @Test
  public void historyIsPerRun() {
    // Acceptance falls by one token per prompt per checkpoint on both runs, so T flags wherever
    // it has a full window.
    EvaluationData d = data(ReplayerTest::declining, Set.of());
    FlagMatrix run2 = replay(d, Run.RUN2, "T");
    for (int s = 0; s < 4; s++) {
      for (int i = 0; i < 3; i++) {
        assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, run2.kind(s, i));
        assertFalse(run2.flagged(s, i));
      }
      assertEquals(700L, (long) run2.steps().get(3));
      assertEquals(DetectorVerdict.Kind.REGRESSION, run2.kind(s, 3));
    }
    FlagMatrix run1 = replay(d, Run.RUN1, "T");
    for (int s = 0; s < 4; s++) {
      assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, run1.kind(s, 0)); // step 100
      assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, run1.kind(s, 1)); // step 200
      assertEquals(300L, (long) run1.steps().get(2));
      assertEquals(DetectorVerdict.Kind.REGRESSION, run1.kind(s, 2));
    }
  }

  @Test
  public void insufficientCountsAsNoFlag() {
    EvaluationData d = data(ReplayerTest::declining, Set.of());
    FlagMatrix run1 = replay(d, Run.RUN1, "T");
    assertEquals(DetectorVerdict.Kind.INSUFFICIENT_DATA, run1.kind(0, 0));
    assertFalse(run1.flagged(0, 0));
    assertTrue(run1.flagged(0, 2));
  }

  @Test
  public void missingCheckpointIsSkipped() {
    EvaluationData d = data((r, k, s) -> 0, Set.of("qwen-run2/qwen-chat/625"));
    List<Long> steps = replay(d, Run.RUN2, "A").steps();
    assertFalse(steps.contains(625L));
    assertEquals(11, steps.size());
  }

  @Test
  public void b0FlagsALargeDropAndNotAStableRun() {
    EvaluationData d = data((r, k, s) -> r == Run.RUN2 && s >= 1000 ? 6 : 0, Set.of());
    FlagMatrix run2 = replay(d, Run.RUN2, "B0");
    int at1000 = run2.steps().indexOf(1000L);
    for (int s = 0; s < 4; s++) {
      assertFalse(run2.flagged(s, 0));
      assertTrue(run2.flagged(s, at1000));
    }
  }

  @Test
  public void b0RecordsIntervals() {
    EvaluationData d = data((r, k, s) -> s >= 1000 ? 3 : 0, Set.of());
    FlagMatrix b0 = replay(d, Run.RUN1, "B0");
    assertTrue(b0.lower(0, 0).isPresent());
    assertTrue(b0.upper(0, 0).getAsDouble() >= b0.lower(0, 0).getAsDouble());
    FlagMatrix a = replay(d, Run.RUN1, "A");
    assertFalse(a.lower(0, 0).isPresent());
    assertFalse(a.upper(0, 0).isPresent());
  }

  @Test
  public void errorVerdictAborts() {
    EvaluationData d = data((r, k, s) -> 0, Set.of());
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
            return DetectorVerdict.error("broken", Metric.ALPHA, "always");
          }
        };
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                replayer.replay(
                    d.baseline("qwen", ProbeKind.CHAT),
                    d.series("qwen", ProbeKind.CHAT, Run.RUN1),
                    Run.RUN1,
                    subsets,
                    DetectorConfig.of("X", broken)));
    assertTrue(e.getMessage(), e.getMessage().contains("X"));
    assertTrue(e.getMessage(), e.getMessage().contains("step 100"));
    assertTrue(e.getMessage(), e.getMessage().contains("subset 0"));
    assertTrue(e.getMessage(), e.getMessage().contains("qwen-chat"));
  }
}
