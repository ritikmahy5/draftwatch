package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** The cross-check against the live detection log, on synthetic data. */
public class LogCrossCheckTest {
  private final MetricCalculator calc = new MetricCalculator();
  private final List<DetectorConfig> configs = DetectorConfigs.all(calc);
  private EvaluationData data;
  private List<DetectionRecord> log;
  private int compared;

  @Before
  public void setUp() {
    InMemoryResultRepository results = new InMemoryResultRepository();
    InMemoryBaselineRepository baselines = new InMemoryBaselineRepository();
    ScoringFixtures.family(
        results, baselines, "qwen", 20, (r, k, s) -> (int) (s / 300), Set.of());
    data = EvaluationData.load(results, baselines);
    log = new ArrayList<>();
    // What watch logged: B0, A, and T on every stored result. Checkpoint 0 is the baseline itself.
    for (ProbeKind kind : ProbeKind.values()) {
      for (Run run : Run.values()) {
        List<Measurement> history = data.series("qwen", kind, run).inStepOrder();
        for (Measurement m : history) {
          long step = m.provenance().checkpoint().step();
          Optional<Measurement> base =
              step == 0 ? Optional.empty() : Optional.of(data.baseline("qwen", kind));
          for (DetectorConfig c : configs) {
            if (!c.id().equals("B1")) {
              log.add(ScoringFixtures.record(m, c.detector().evaluate(m, base, history)));
            }
          }
          if (step != 0) {
            compared++;
          }
        }
      }
    }
  }

  @Test
  public void crossCheckPassesOnMatchingLog() {
    LogCrossCheck.Result r = LogCrossCheck.check(data, log, configs);
    assertEquals(compared, r.compared());
    assertEquals(4 * (12 + 12), r.compared());
    assertTrue(r.trendMismatches().isEmpty());
  }

  private int indexOf(String job, String detectorPrefix) {
    for (int i = 0; i < log.size(); i++) {
      DetectionRecord r = log.get(i);
      if (r.jobId().equals(job) && r.detector().get().startsWith(detectorPrefix)) {
        return i;
      }
    }
    throw new AssertionError("no record for " + job);
  }

  private void replace(String job, String prefix, DetectorVerdict changed) {
    int i = indexOf(job, prefix);
    Measurement m =
        data.series("qwen", ProbeKind.CODE, Run.RUN2).at(900).get();
    log.set(i, ScoringFixtures.record(m, changed));
  }

  @Test
  public void crossCheckFailsOnChangedInterval() {
    String job = ScoringFixtures.jobId("qwen", Run.RUN2, ProbeKind.CODE, 900);
    DetectionRecord original = log.get(indexOf(job, "paired_bootstrap"));
    DetectorVerdict changed =
        DetectorVerdict.builder(
                DetectorVerdict.Kind.valueOf(original.kind()),
                original.detector().get(),
                dev.draftwatch.domain.Metric.ALPHA)
            .observed(original.observed().getAsDouble())
            .threshold(original.threshold().getAsDouble())
            .interval(original.intervalLower().getAsDouble(),
                Math.nextUp(original.intervalUpper().getAsDouble()))
            .baselineJobId(original.baselineJobId().get())
            .explanation(original.explanation())
            .build();
    replace(job, "paired_bootstrap", changed);
    DataException e =
        assertThrows(DataException.class, () -> LogCrossCheck.check(data, log, configs));
    assertTrue(e.getMessage(), e.getMessage().contains(job));
    assertTrue(e.getMessage(), e.getMessage().contains("paired_bootstrap"));
  }

  @Test
  public void crossCheckFailsOnMissingRecord() {
    String job = ScoringFixtures.jobId("qwen", Run.RUN1, ProbeKind.CHAT, 500);
    log.remove(indexOf(job, "absolute_drop"));
    DataException e =
        assertThrows(DataException.class, () -> LogCrossCheck.check(data, log, configs));
    assertTrue(e.getMessage(), e.getMessage().contains(job));
  }

  @Test
  public void crossCheckReportsTrendMismatch() {
    String job = ScoringFixtures.jobId("qwen", Run.RUN2, ProbeKind.CODE, 900);
    DetectionRecord original = log.get(indexOf(job, "trend"));
    DetectorVerdict changed =
        DetectorVerdict.insufficient(
            original.detector().get(), dev.draftwatch.domain.Metric.ALPHA, "fewer results then");
    replace(job, "trend", changed);
    LogCrossCheck.Result r = LogCrossCheck.check(data, log, configs);
    assertEquals(1, r.trendMismatches().size());
    assertTrue(r.trendMismatches().get(0), r.trendMismatches().get(0).contains(job));
  }
}
