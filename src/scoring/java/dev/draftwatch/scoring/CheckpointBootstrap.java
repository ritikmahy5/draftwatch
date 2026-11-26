package dev.draftwatch.scoring;

import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

/**
 * Percentile-bootstrap intervals for pooled per-checkpoint rates (docs/EVALUATION.md, "What the
 * rates can and cannot say"): the subsets measure sampling error given 12 checkpoints per run, so
 * the uncertainty of a summary rate comes from resampling checkpoints, not subsets. Randomness is
 * a seeded {@code java.util.Random}, and the bounds use the type-7 quantile, as in
 * draftwatch's {@code PairedBootstrap}.
 */
public final class CheckpointBootstrap {
  private CheckpointBootstrap() {}

  /**
   * The {@code confidence} interval {@code {lower, upper}} of {@code sum(flagged) / sum(total)}
   * when checkpoints are resampled with replacement. A resample whose pooled total is 0 has no
   * rate and is skipped.
   *
   * @return empty if there are no checkpoints or every resample's total is 0
   */
  public static Optional<double[]> interval(
      int[] flaggedPerCheckpoint,
      int[] totalPerCheckpoint,
      int resamples,
      double confidence,
      long seed) {
    int n = flaggedPerCheckpoint.length;
    if (totalPerCheckpoint.length != n) {
      throw new IllegalArgumentException(
          n + " flagged counts but " + totalPerCheckpoint.length + " totals");
    }
    if (resamples < 1) {
      throw new IllegalArgumentException("resamples must be >= 1, was " + resamples);
    }
    if (!(confidence > 0 && confidence < 1)) {
      throw new IllegalArgumentException("confidence must be in (0, 1), was " + confidence);
    }
    if (n == 0) {
      return Optional.empty();
    }
    Random rng = new Random(seed);
    double[] rates = new double[resamples];
    int kept = 0;
    for (int r = 0; r < resamples; r++) {
      long flagged = 0;
      long total = 0;
      for (int i = 0; i < n; i++) {
        int j = rng.nextInt(n);
        flagged += flaggedPerCheckpoint[j];
        total += totalPerCheckpoint[j];
      }
      if (total > 0) {
        rates[kept++] = (double) flagged / total;
      }
    }
    if (kept == 0) {
      return Optional.empty();
    }
    double[] sorted = Arrays.copyOf(rates, kept);
    Arrays.sort(sorted);
    return Optional.of(
        new double[] {
          quantile(sorted, (1 - confidence) / 2), quantile(sorted, (1 + confidence) / 2)
        });
  }

  /** Type-7 quantile of sorted {@code x}, the rule of {@code PairedBootstrap.quantile}. */
  static double quantile(double[] x, double q) {
    double h = (x.length - 1) * q;
    int lo = (int) Math.floor(h);
    int hi = Math.min(lo + 1, x.length - 1);
    return x[lo] + (h - lo) * (x[hi] - x[lo]);
  }
}
