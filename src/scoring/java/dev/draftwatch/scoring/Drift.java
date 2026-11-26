package dev.draftwatch.scoring;

import dev.draftwatch.domain.Measurement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The secondary metric "drift against the control" (docs/EVALUATION.md, "Metrics"): at steps
 * 700–1,200, where both runs save a checkpoint, run 2's aggregate {@code alpha} minus run 1's on
 * the same probe.
 */
public final class Drift {
  static final List<Long> STEPS = List.of(700L, 800L, 900L, 1000L, 1100L, 1200L);

  private Drift() {}

  /** One point per shared step where both runs have a result, in step order. */
  public static List<DriftPoint> of(Series run1, Series run2) {
    List<DriftPoint> out = new ArrayList<>();
    for (long step : STEPS) {
      Optional<Measurement> a = run1.at(step);
      Optional<Measurement> b = run2.at(step);
      if (a.isPresent() && b.isPresent()) {
        out.add(
            new DriftPoint(
                step,
                b.get().report().aggregate().alphaMean()
                    - a.get().report().aggregate().alphaMean()));
      }
    }
    return out;
  }
}
