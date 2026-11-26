package dev.draftwatch.scoring;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionInsufficientData;
import dev.draftwatch.events.DetectionOk;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.RegressionDetected;
import dev.draftwatch.store.BaselineRepository;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.testing.CountsMeasurements;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Synthetic evaluation data for the scoring tests: every count is invented. Prompt i has
 * {@code 5 + i % 7} steps, four proposed tokens per step, and a base acceptance between 40% and
 * 84%; a checkpoint's {@code shift} removes that many accepted tokens from every prompt.
 */
final class ScoringFixtures {
  static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

  /** The number of accepted tokens removed from every prompt at a checkpoint. */
  interface Shift {
    int at(Run run, ProbeKind kind, long step);
  }

  private ScoringFixtures() {}

  static List<PromptCounts> prompts(int pool, int shift) {
    List<PromptCounts> out = new ArrayList<>();
    for (int i = 0; i < pool; i++) {
      long steps = 5 + i % 7;
      long proposed = steps * 4;
      long accepted = Math.max(0, proposed * (40 + (i * 13) % 45) / 100 - shift);
      out.add(PromptCounts.of(i, steps, proposed, accepted));
    }
    return out;
  }

  static String fingerprint(String family, Run run, long step) {
    long salt =
        (family.equals("qwen") ? 1L : 2L) << 40 | (run == Run.RUN2 && step != 0 ? 1L << 32 : 0);
    return "sampled-" + String.format("%064x", salt | step);
  }

  static String probeHash(String family, ProbeKind kind) {
    return String.format("%064x", (family.equals("qwen") ? 100 : 200) + kind.ordinal());
  }

  static String jobId(String family, Run run, ProbeKind kind, long step) {
    return "j-" + family + "-" + run.suffix() + "-" + kind.suffix() + "-" + step;
  }

  /** Stores one family's measurements, except {@code skip}ped "target/probe/step" keys. */
  static void family(
      ResultRepository results,
      BaselineRepository baselines,
      String family,
      int pool,
      Shift shift,
      Set<String> skip) {
    CountsMeasurements counts = new CountsMeasurements(4);
    Measurement zero = null;
    for (ProbeKind kind : ProbeKind.values()) {
      for (Run run : Run.values()) {
        for (long step : ExpectedSteps.of(run)) {
          String target = family + "-" + run.suffix();
          String probe = family + "-" + kind.suffix();
          if (skip.contains(target + "/" + probe + "/" + step)) {
            continue;
          }
          Measurement m =
              counts
                  .probeHash(probeHash(family, kind))
                  .measurement(
                      target,
                      probe,
                      fingerprint(family, run, step),
                      jobId(family, run, kind, step),
                      step,
                      T0.plusSeconds(step * 10 + kind.ordinal()),
                      prompts(pool, shift.at(run, kind, step)));
          results.append(m);
          if (step == 0) {
            zero = m;
          }
        }
      }
    }
    baselines.set(Baseline.of(zero.provenance().checkpoint(), T0, Baseline.Source.MANUAL));
    baselines.set(
        Baseline.of(
            family + "-run2",
            zero.fingerprint(),
            zero.provenance().checkpoint().path(),
            0,
            T0,
            Baseline.Source.MANUAL));
  }

  /**
   * What {@code watch} logged: B0, A, and T on every stored result, with the repository's history
   * of the target and probe; checkpoint 0 is the baseline itself, compared with nothing.
   */
  static List<DetectionRecord> watchLog(EvaluationData data, List<DetectorConfig> configs) {
    List<DetectionRecord> log = new ArrayList<>();
    for (String family : data.families()) {
      for (ProbeKind kind : ProbeKind.values()) {
        for (Run run : Run.values()) {
          List<Measurement> history = data.series(family, kind, run).inStepOrder();
          for (Measurement m : history) {
            long step = m.provenance().checkpoint().step();
            java.util.Optional<Measurement> base =
                step == 0
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(data.baseline(family, kind));
            for (DetectorConfig c : configs) {
              if (!c.id().equals("B1")) {
                log.add(record(m, c.detector().evaluate(m, base, history)));
              }
            }
          }
        }
      }
    }
    return log;
  }

  /** The record {@code watch} would have logged for {@code verdict} on {@code m}. */
  static DetectionRecord record(Measurement m, DetectorVerdict verdict) {
    DetectionSubject subject =
        DetectionSubject.of(m, Paths.get("/scratch/user/state/results/" + m.jobId() + ".json"));
    DetectionEvent event;
    switch (verdict.kind()) {
      case REGRESSION:
        event = new RegressionDetected(T0, subject, verdict);
        break;
      case OK:
        event = new DetectionOk(T0, subject, verdict);
        break;
      case INSUFFICIENT_DATA:
        event = new DetectionInsufficientData(T0, subject, verdict);
        break;
      default:
        throw new IllegalArgumentException("no record for " + verdict.kind());
    }
    return DetectionRecord.of(event);
  }
}
