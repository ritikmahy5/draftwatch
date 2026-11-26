package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;

import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/** Run 2 minus run 1 at the shared steps, on synthetic data. */
public class DriftTest {
  private EvaluationData data(Set<String> skip) {
    InMemoryResultRepository results = new InMemoryResultRepository();
    InMemoryBaselineRepository baselines = new InMemoryBaselineRepository();
    ScoringFixtures.family(
        results, baselines, "qwen", 20, (r, k, s) -> r == Run.RUN2 ? 2 : 0, skip);
    return EvaluationData.load(results, baselines);
  }

  @Test
  public void driftUsesAggregateAlpha() {
    EvaluationData d = data(Set.of());
    Series run1 = d.series("qwen", ProbeKind.CHAT, Run.RUN1);
    Series run2 = d.series("qwen", ProbeKind.CHAT, Run.RUN2);
    List<DriftPoint> drift = Drift.of(run1, run2);
    assertEquals(
        List.of(700L, 800L, 900L, 1000L, 1100L, 1200L),
        drift.stream().map(DriftPoint::step).collect(Collectors.toList()));
    double expected =
        run2.at(900).get().report().aggregate().alphaMean()
            - run1.at(900).get().report().aggregate().alphaMean();
    assertEquals(expected, drift.get(2).run2MinusRun1Alpha(), 0.0);
    assertEquals(true, expected < 0);
  }

  @Test
  public void driftSkipsMissingSteps() {
    EvaluationData d =
        data(Set.of("qwen-run1/qwen-code/800", "qwen-run2/qwen-code/1100"));
    List<DriftPoint> drift =
        Drift.of(
            d.series("qwen", ProbeKind.CODE, Run.RUN1), d.series("qwen", ProbeKind.CODE, Run.RUN2));
    assertEquals(
        List.of(700L, 900L, 1000L, 1200L),
        drift.stream().map(DriftPoint::step).collect(Collectors.toList()));
  }
}
