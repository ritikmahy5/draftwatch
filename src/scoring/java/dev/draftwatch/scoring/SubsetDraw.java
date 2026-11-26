package dev.draftwatch.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Draws probe subsets without replacement from a prompt pool (docs/EVALUATION.md, "Scoring the
 * detectors"). One {@code java.util.Random(seed)} drives the whole draw; each subset is a partial
 * Fisher–Yates shuffle of the identity permutation, sorted ascending. Subsets may repeat across
 * draws, as independent users' probes could.
 */
public final class SubsetDraw {
  private SubsetDraw() {}

  /**
   * {@code count} subsets of {@code n} indices from {@code 0 … poolSize−1}.
   *
   * @throws IllegalArgumentException unless {@code 1 <= n <= poolSize} and {@code count >= 1}
   */
  public static List<Subset> draw(int poolSize, int n, int count, long seed) {
    if (n < 1 || n > poolSize) {
      throw new IllegalArgumentException(
          "subset size must be in 1.." + poolSize + ", was " + n);
    }
    if (count < 1) {
      throw new IllegalArgumentException("count must be >= 1, was " + count);
    }
    Random rng = new Random(seed);
    int[] pool = new int[poolSize];
    List<Subset> out = new ArrayList<>(count);
    for (int k = 0; k < count; k++) {
      for (int i = 0; i < poolSize; i++) {
        pool[i] = i;
      }
      for (int i = 0; i < n; i++) {
        int j = i + rng.nextInt(poolSize - i);
        int t = pool[i];
        pool[i] = pool[j];
        pool[j] = t;
      }
      int[] chosen = Arrays.copyOf(pool, n);
      Arrays.sort(chosen);
      out.add(new Subset(k, chosen));
    }
    return out;
  }
}
