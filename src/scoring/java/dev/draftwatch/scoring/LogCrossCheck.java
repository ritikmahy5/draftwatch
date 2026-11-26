package dev.draftwatch.scoring;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.store.DetectionRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Checks, before scoring, that draftwatch's detectors give the verdicts {@code watch} logged
 * during the runs: B0 and A on every stored full measurement (except checkpoint 0, which was
 * compared with nothing) must match their {@code detections.log} records exactly in kind,
 * observed value, and interval bounds. T is compared too, but a mismatch is only reported:
 * {@code watch}'s T verdict depended on which results existed when it ran.
 */
public final class LogCrossCheck {
  /** How many measurements were compared, and the T mismatches. */
  public static final class Result {
    private final int compared;
    private final List<String> trendMismatches;

    Result(int compared, List<String> trendMismatches) {
      this.compared = compared;
      this.trendMismatches = List.copyOf(trendMismatches);
    }

    public int compared() {
      return compared;
    }

    public List<String> trendMismatches() {
      return trendMismatches;
    }
  }

  private LogCrossCheck() {}

  /**
   * @throws DataException naming the job and detector of the first B0 or A record that is
   *     missing or differs
   */
  public static Result check(
      EvaluationData data, List<DetectionRecord> log, List<DetectorConfig> configs) {
    DetectorConfig b0 = byId(configs, "B0");
    DetectorConfig a = byId(configs, "A");
    DetectorConfig t = byId(configs, "T");
    Map<String, DetectionRecord> records = new HashMap<>();
    for (DetectionRecord r : log) {
      if (r.detector().isPresent()) {
        records.put(r.jobId() + "\n" + r.detector().get(), r);
      }
    }
    int compared = 0;
    List<String> trendMismatches = new ArrayList<>();
    for (String family : data.families()) {
      for (ProbeKind kind : ProbeKind.values()) {
        Optional<Measurement> base = Optional.of(data.baseline(family, kind));
        for (Run run : Run.values()) {
          List<Measurement> series = data.series(family, kind, run).inStepOrder();
          List<Measurement> history = new ArrayList<>();
          for (Measurement m : series) {
            history.add(m);
            if (m.provenance().checkpoint().step() == 0) {
              continue;
            }
            for (DetectorConfig c : List.of(b0, a)) {
              Optional<String> problem =
                  difference(records, m, c.detector().evaluate(m, base, history));
              if (problem.isPresent()) {
                throw new DataException("detections.log: " + problem.get());
              }
            }
            difference(records, m, t.detector().evaluate(m, base, List.copyOf(history)))
                .ifPresent(trendMismatches::add);
            compared++;
          }
        }
      }
    }
    return new Result(compared, trendMismatches);
  }

  private static DetectorConfig byId(List<DetectorConfig> configs, String id) {
    for (DetectorConfig c : configs) {
      if (c.id().equals(id)) {
        return c;
      }
    }
    throw new IllegalArgumentException("no detector configuration " + id);
  }

  private static Optional<String> difference(
      Map<String, DetectionRecord> records, Measurement m, DetectorVerdict replayed) {
    String where = "job " + m.jobId() + " (" + m.provenance().targetName() + " "
        + m.provenance().probeId() + " step " + m.provenance().checkpoint().step() + ") "
        + replayed.detector();
    DetectionRecord logged = records.get(m.jobId() + "\n" + replayed.detector());
    if (logged == null) {
      return Optional.of(where + ": no logged verdict");
    }
    if (!logged.kind().equals(replayed.kind().name())
        || !logged.observed().equals(replayed.observed())
        || !logged.intervalLower().equals(replayed.intervalLower())
        || !logged.intervalUpper().equals(replayed.intervalUpper())) {
      return Optional.of(where + ": logged " + describe(logged.kind(), logged.observed(),
          logged.intervalLower(), logged.intervalUpper()) + ", replayed "
          + describe(replayed.kind().name(), replayed.observed(), replayed.intervalLower(),
              replayed.intervalUpper()));
    }
    return Optional.empty();
  }

  private static String describe(
      String kind, OptionalDouble observed, OptionalDouble lower, OptionalDouble upper) {
    return kind + " observed " + text(observed) + " [" + text(lower) + ", " + text(upper) + "]";
  }

  private static String text(OptionalDouble v) {
    return v.isPresent() ? Double.toString(v.getAsDouble()) : "none";
  }
}
