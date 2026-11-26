package dev.draftwatch.scoring;

import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Measurement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Replays one run through one detector configuration as each subset's user would have seen it:
 * every compared checkpoint is evaluated against the subset of the baseline, with the history
 * {@code watch} gives (the run's own results up to that step; run 1's includes checkpoint 0, run
 * 2's never does, since history is kept per target).
 */
public final class Replayer {
  private final SubsetMeasurements subsets;

  public Replayer(SubsetMeasurements subsets) {
    this.subsets = Objects.requireNonNull(subsets, "subsets");
  }

  /**
   * @throws IllegalStateException on an ERROR verdict, naming the configuration, probe, step, and
   *     subset; validated, comparable inputs never give one
   */
  public FlagMatrix replay(
      Measurement baseline, Series run, Run which, List<Subset> draw, DetectorConfig config) {
    List<Measurement> full = run.inStepOrder();
    List<Long> steps = new ArrayList<>();
    for (Measurement m : full) {
      long step = m.provenance().checkpoint().step();
      if (step != 0) {
        steps.add(step);
      }
    }
    DetectorVerdict.Kind[][] kinds = new DetectorVerdict.Kind[draw.size()][steps.size()];
    double[][] lower = new double[draw.size()][steps.size()];
    double[][] upper = new double[draw.size()][steps.size()];
    for (int s = 0; s < draw.size(); s++) {
      Subset subset = draw.get(s);
      Optional<Measurement> base = Optional.of(subsets.of(baseline, subset));
      List<Measurement> history = new ArrayList<>(full.size());
      int column = 0;
      for (Measurement m : full) {
        Measurement current = subsets.of(m, subset);
        history.add(current);
        long step = m.provenance().checkpoint().step();
        if (step == 0) {
          continue;
        }
        DetectorVerdict v = config.detector().evaluate(current, base, List.copyOf(history));
        if (v.kind() == DetectorVerdict.Kind.ERROR) {
          throw new IllegalStateException(config.id() + " gave ERROR for "
              + m.provenance().targetName() + " " + m.provenance().probeId() + " (" + which
              + ") at step " + step + " on subset " + subset.ordinal() + " of size "
              + subset.size() + ": " + v.explanation());
        }
        kinds[s][column] = v.kind();
        lower[s][column] = v.intervalLower().orElse(Double.NaN);
        upper[s][column] = v.intervalUpper().orElse(Double.NaN);
        column++;
      }
    }
    return new FlagMatrix(steps, kinds, lower, upper);
  }
}
