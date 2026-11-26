package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** How the live watch behaved, on synthetic data and records. */
public class OperationSummaryTest {
  private static final String A = "absolute_drop(metric=alpha, max_drop=0.02)";

  private EvaluationData data(Set<String> skip) {
    InMemoryResultRepository results = new InMemoryResultRepository();
    InMemoryBaselineRepository baselines = new InMemoryBaselineRepository();
    ScoringFixtures.family(results, baselines, "qwen", 20, (r, k, s) -> 0, skip);
    return EvaluationData.load(results, baselines);
  }

  private static DetectionRecord flag(Measurement m) {
    return ScoringFixtures.record(
        m,
        DetectorVerdict.builder(DetectorVerdict.Kind.REGRESSION, A, Metric.ALPHA)
            .observed(-0.03)
            .threshold(-0.02)
            .explanation("dropped")
            .build());
  }

  private static DetectionRecord ok(Measurement m) {
    return ScoringFixtures.record(
        m,
        DetectorVerdict.builder(DetectorVerdict.Kind.OK, A, Metric.ALPHA)
            .observed(0.0)
            .threshold(-0.02)
            .explanation("fine")
            .build());
  }

  private static Map<String, List<CheckpointRow>> rows(long step, double[] ref) {
    return Map.of(
        "qwen-chat",
        List.of(CheckpointRow.of(Run.RUN2, step, ReferenceDifference.of(ref[0], ref[1], ref[2]))),
        "qwen-code",
        List.of());
  }

  @Test
  public void pairsLiveFlagWithReferenceLabel() {
    EvaluationData d = data(Set.of());
    Measurement live = d.series("qwen", ProbeKind.CHAT_LIVE, Run.RUN2).at(800).get();
    Measurement reference = d.series("qwen", ProbeKind.CHAT, Run.RUN2).at(800).get();
    List<DetectionRecord> log = new ArrayList<>(List.of(flag(live), ok(live), flag(reference)));
    OperationSummary s =
        OperationSummary.of(d, log, rows(800, new double[] {-0.03, -0.04, -0.02}));
    assertEquals(1, s.liveFlags().size());
    LiveFlag f = s.liveFlags().get(0);
    assertEquals("qwen-run2", f.target());
    assertEquals("qwen-chat-live", f.probeId());
    assertEquals(800, f.step());
    assertEquals(A, f.detector());
    assertEquals(live.jobId(), f.jobId());
    assertEquals("REGRESSED", f.referenceLabel());
  }

  @Test
  public void liveFlagOnMissingReferenceSaysNoReference() {
    EvaluationData d = data(Set.of("qwen-run2/qwen-chat/625"));
    Measurement live = d.series("qwen", ProbeKind.CHAT_LIVE, Run.RUN2).at(625).get();
    OperationSummary s =
        OperationSummary.of(d, List.of(flag(live)), rows(800, new double[] {0, -0.01, 0.01}));
    assertEquals("no reference", s.liveFlags().get(0).referenceLabel());
    assertEquals(1, s.missing().size());
  }

  @Test
  public void countsMeasurementsPerCheckpoint() {
    EvaluationData d = data(Set.of());
    Measurement live = d.series("qwen", ProbeKind.CODE_LIVE, Run.RUN1).at(300).get();
    List<DetectionRecord> log = new ArrayList<>(List.of(ok(live), flag(live)));
    assertEquals(1, OperationSummary.of(d, log, rows(800, new double[] {0, -0.01, 0.01}))
        .measurementsPerCheckpointMax());
    // A second job for the same checkpoint and probe, as a re-measurement would log.
    Measurement again =
        new dev.draftwatch.testing.CountsMeasurements(4)
            .probeHash(live.probeHash())
            .measurement(
                "qwen-run1",
                "qwen-code-live",
                live.fingerprint(),
                "j-again",
                300,
                ScoringFixtures.T0,
                ScoringFixtures.prompts(20, 0));
    log.add(ok(again));
    OperationSummary s = OperationSummary.of(d, log, rows(800, new double[] {0, -0.01, 0.01}));
    assertEquals(2, s.measurementsPerCheckpointMax());
    assertTrue(s.toString(), s.remeasured().get(0).contains("j-again"));
  }
}
