package dev.draftwatch.stats;

import dev.draftwatch.domain.PromptCounts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.function.Function;

/**
 * Paired bootstrap over prompts (ARCHITECTURE.md, "Statistics"). The algorithm
 * is fixed exactly, so a seed reproduces an interval bit for bit on every JVM and can be checked
 * against an independent implementation ({@code scripts/bootstrap_reference.py}):
 *
 * <ol>
 *   <li>{@code rng = new java.util.Random(seed)}.
 *   <li>For each of {@code resamples} resamples: draw {@code n} indices, in order, with
 *       {@code rng.nextInt(n)}; compute the statistic on the current and on the baseline prompts
 *       at those same indices; record the difference current − baseline.
 *   <li>Sort the differences. The interval bounds are the {@code (1 − c) / 2} and
 *       {@code (1 + c) / 2} quantiles by linear interpolation between order statistics (type 7,
 *       numpy's default): with {@code h = (B − 1) q} and {@code l = ⌊h⌋}, the quantile is
 *       {@code x[l] + (h − l)(x[l+1] − x[l])}.
 * </ol>
 *
 * <p>If the statistic is undefined on the full data or on any resample, the run fails: dropping
 * such draws would bias the interval.
 */
public final class PairedBootstrap {
  private PairedBootstrap() {}

  /**
   * Runs the bootstrap.
   *
   * @param current per-prompt counts of the current measurement, by prompt index
   * @param baseline per-prompt counts of the baseline measurement, same prompts in the same order
   * @param statistic the metric under the probe's estimator; empty when undefined
   * @throws IllegalArgumentException if the lists differ in length or are empty, if
   *     {@code resamples < 1}, or unless {@code 0 < confidence < 1}
   * @throws UndefinedStatisticException naming the data or resample where it is undefined
   */
  public static BootstrapInterval run(
      List<PromptCounts> current,
      List<PromptCounts> baseline,
      Function<List<PromptCounts>, OptionalDouble> statistic,
      int resamples,
      double confidence,
      long seed) {
    int n = current.size();
    if (n == 0 || baseline.size() != n) {
      throw new IllegalArgumentException(
          "paired bootstrap needs two equal-length, non-empty prompt lists; got " + n + " and "
              + baseline.size());
    }
    if (resamples < 1) {
      throw new IllegalArgumentException("resamples must be >= 1, was " + resamples);
    }
    if (!(confidence > 0 && confidence < 1)) {
      throw new IllegalArgumentException("confidence must be in (0, 1), was " + confidence);
    }
    double observed =
        value(statistic, current, "the current measurement")
            - value(statistic, baseline, "the baseline measurement");
    Random rng = new Random(seed);
    double[] differences = new double[resamples];
    List<PromptCounts> c = new ArrayList<>(n);
    List<PromptCounts> b = new ArrayList<>(n);
    for (int r = 0; r < resamples; r++) {
      c.clear();
      b.clear();
      for (int i = 0; i < n; i++) {
        int j = rng.nextInt(n);
        c.add(current.get(j));
        b.add(baseline.get(j));
      }
      String where = "bootstrap resample " + r;
      differences[r] =
          value(statistic, c, where + " of the current measurement")
              - value(statistic, b, where + " of the baseline measurement");
    }
    Arrays.sort(differences);
    return new BootstrapInterval(
        observed,
        quantile(differences, (1 - confidence) / 2),
        quantile(differences, (1 + confidence) / 2),
        confidence,
        resamples,
        seed);
  }

  /** Type-7 quantile of sorted {@code x}. */
  static double quantile(double[] x, double q) {
    double h = (x.length - 1) * q;
    int lo = (int) Math.floor(h);
    int hi = Math.min(lo + 1, x.length - 1);
    return x[lo] + (h - lo) * (x[hi] - x[lo]);
  }

  private static double value(
      Function<List<PromptCounts>, OptionalDouble> statistic,
      List<PromptCounts> prompts,
      String where) {
    OptionalDouble v = statistic.apply(prompts);
    if (v.isEmpty()) {
      throw new UndefinedStatisticException("the statistic is undefined on " + where);
    }
    return v.getAsDouble();
  }
}
