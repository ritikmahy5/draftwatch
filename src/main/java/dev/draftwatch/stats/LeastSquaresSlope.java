package dev.draftwatch.stats;

import java.util.List;

/**
 * Ordinary least-squares slope of values against their index 0 … n−1 (ARCHITECTURE.md,
 * "Statistics"): {@code Σ (x − x̄)(y − ȳ) / Σ (x − x̄)²}, in value per index step.
 */
public final class LeastSquaresSlope {
  private LeastSquaresSlope() {}

  /** @throws IllegalArgumentException if there are fewer than two values */
  public static double slope(List<Double> y) {
    int n = y.size();
    if (n < 2) {
      throw new IllegalArgumentException("a slope needs at least 2 values, got " + n);
    }
    double xMean = (n - 1) / 2.0;
    double yMean = 0;
    for (double v : y) {
      yMean += v;
    }
    yMean /= n;
    double sxy = 0;
    double sxx = 0;
    for (int i = 0; i < n; i++) {
      double dx = i - xMean;
      sxy += dx * (y.get(i) - yMean);
      sxx += dx * dx;
    }
    return sxy / sxx;
  }
}
