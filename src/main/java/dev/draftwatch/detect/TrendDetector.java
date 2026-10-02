package dev.draftwatch.detect;

import dev.draftwatch.config.TrendSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.stats.LeastSquaresSlope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code trend}: a regression when the least-squares slope of the metric against checkpoint index
 * (0 … window−1) over the last {@code window} comparable measurements is below
 * {@code max_slope} (SPEC.md F5).
 *
 * <p>The window (DECISIONS.md D46): measurements comparable with the current one (the
 * Comparability guard), one per checkpoint (the current measurement for its own checkpoint, the
 * latest result for any other), at steps up to the current one; the last {@code window} of them
 * in step order. With fewer, the verdict is INSUFFICIENT_DATA.
 */
public final class TrendDetector implements RegressionDetector {
  private static final Comparator<Measurement> STEP_ORDER =
      Comparator.comparingLong((Measurement m) -> m.provenance().checkpoint().step())
          .thenComparing(m -> m.provenance().endTime())
          .thenComparing(Measurement::jobId);

  private final TrendSpec spec;

  public TrendDetector(TrendSpec spec) {
    this.spec = Objects.requireNonNull(spec, "spec");
  }

  @Override
  public String describe() {
    return spec.describe();
  }

  @Override
  public Metric metric() {
    return spec.metric();
  }

  @Override
  public DetectorVerdict evaluate(
      Measurement current, Optional<Measurement> baseline, List<Measurement> history) {
    long step = current.provenance().checkpoint().step();
    Map<String, Measurement> perCheckpoint = new LinkedHashMap<>();
    int incomparable = 0;
    List<Measurement> sorted = new ArrayList<>(history);
    sorted.sort(STEP_ORDER);
    for (Measurement m : sorted) {
      if (m.jobId().equals(current.jobId()) || m.fingerprint().equals(current.fingerprint())) {
        continue;
      }
      if (m.provenance().checkpoint().step() > step) {
        continue;
      }
      if (!Comparability.comparable(current, m)) {
        incomparable++;
        continue;
      }
      perCheckpoint.put(m.fingerprint(), m); // later results of a checkpoint replace earlier
    }
    perCheckpoint.put(current.fingerprint(), current);
    List<Measurement> window = new ArrayList<>(perCheckpoint.values());
    window.sort(STEP_ORDER);
    String excluded = incomparable == 0 ? "" : "; " + incomparable + " incomparable excluded";
    if (window.size() < spec.window()) {
      return DetectorVerdict.insufficient(
          describe(),
          metric(),
          "only " + window.size() + " of " + spec.window()
              + " comparable checkpoints up to step " + step + excluded);
    }
    window = window.subList(window.size() - spec.window(), window.size());
    List<Double> values = new ArrayList<>();
    for (Measurement m : window) {
      values.add(BaselineDetector.aggregate(m, metric()));
    }
    double slope = LeastSquaresSlope.slope(values);
    boolean regression = slope < spec.maxSlope();
    String text =
        "slope of " + metric().wireName() + " over steps "
            + window.get(0).provenance().checkpoint().step() + ".." + step + " is " + slope
            + " per checkpoint" + (regression ? ", below " : ", not below ") + spec.maxSlope()
            + excluded;
    return DetectorVerdict.builder(
            regression ? DetectorVerdict.Kind.REGRESSION : DetectorVerdict.Kind.OK,
            describe(),
            metric())
        .observed(slope)
        .threshold(spec.maxSlope())
        .explanation(text)
        .build();
  }
}
