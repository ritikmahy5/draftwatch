package dev.draftwatch.scoring;

import dev.draftwatch.detect.DetectorVerdict;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One configuration's verdicts on one run: for every subset and compared checkpoint, the verdict
 * kind, whether it flagged, and (for the paired bootstrap) the interval bounds.
 */
public final class FlagMatrix {
  private final List<Long> steps;
  private final DetectorVerdict.Kind[][] kinds;
  private final double[][] lower;
  private final double[][] upper;

  /** Bounds are {@code NaN} where the verdict has none; the arrays are not copied. */
  FlagMatrix(List<Long> steps, DetectorVerdict.Kind[][] kinds, double[][] lower, double[][] upper) {
    this.steps = List.copyOf(steps);
    this.kinds = kinds;
    this.lower = lower;
    this.upper = upper;
  }

  /** The compared checkpoints with a result, in step order. */
  public List<Long> steps() {
    return steps;
  }

  public int subsets() {
    return kinds.length;
  }

  public DetectorVerdict.Kind kind(int subset, int stepIndex) {
    return kinds[subset][stepIndex];
  }

  /** A REGRESSION verdict; insufficient data counts as no flag. */
  public boolean flagged(int subset, int stepIndex) {
    return kinds[subset][stepIndex] == DetectorVerdict.Kind.REGRESSION;
  }

  public OptionalDouble lower(int subset, int stepIndex) {
    return bound(lower[subset][stepIndex]);
  }

  public OptionalDouble upper(int subset, int stepIndex) {
    return bound(upper[subset][stepIndex]);
  }

  private static OptionalDouble bound(double v) {
    return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
  }
}
