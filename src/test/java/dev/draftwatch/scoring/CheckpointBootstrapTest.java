package dev.draftwatch.scoring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import org.junit.Test;

/** Percentile intervals of pooled rates, resampling checkpoints; inputs are invented. */
public class CheckpointBootstrapTest {
  @Test
  public void bootstrapGolden() {
    Optional<double[]> ci =
        CheckpointBootstrap.interval(
            new int[] {0, 5, 10, 50, 100}, new int[] {100, 100, 100, 100, 100}, 2000, 0.95, 0);
    // Pasted from the first run: this pins determinism.
    assertArrayEquals(new double[] {0.04, 0.7}, ci.get(), 0.0);
  }

  @Test
  public void intervalContainsThePooledRate() {
    double[] ci =
        CheckpointBootstrap.interval(
                new int[] {0, 5, 10, 50, 100}, new int[] {100, 100, 100, 100, 100}, 2000, 0.95, 0)
            .get();
    double pooled = 165.0 / 500;
    assertTrue(ci[0] <= pooled && pooled <= ci[1]);
    assertTrue(ci[0] >= 0 && ci[1] <= 1);
  }

  @Test
  public void bootstrapDegenerate() {
    double[] ci =
        CheckpointBootstrap.interval(new int[] {3, 3, 3}, new int[] {12, 12, 12}, 500, 0.95, 0)
            .get();
    assertEquals(0.25, ci[0], 0.0);
    assertEquals(0.25, ci[1], 0.0);
  }

  @Test
  public void allEmptyTotalsGiveNoInterval() {
    assertFalse(
        CheckpointBootstrap.interval(new int[] {0, 0}, new int[] {0, 0}, 100, 0.95, 0)
            .isPresent());
    assertFalse(CheckpointBootstrap.interval(new int[] {}, new int[] {}, 100, 0.95, 0).isPresent());
  }

  @Test
  public void resamplesWithAnEmptyTotalAreSkipped() {
    // One checkpoint with cells and one without: every kept resample has the first one's rate.
    double[] ci =
        CheckpointBootstrap.interval(new int[] {1, 0}, new int[] {4, 0}, 200, 0.95, 0).get();
    assertEquals(0.25, ci[0], 0.0);
    assertEquals(0.25, ci[1], 0.0);
  }
}
