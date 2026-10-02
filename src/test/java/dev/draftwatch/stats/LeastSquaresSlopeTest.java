package dev.draftwatch.stats;

import static org.junit.Assert.assertEquals;

import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.Test;

/** Exact outputs; the values are invented. */
public class LeastSquaresSlopeTest {
  @Test
  public void slopeOfALineIsItsGradient() {
    assertEquals(2.0, LeastSquaresSlope.slope(List.of(1.0, 3.0, 5.0, 7.0)), 0.0);
    assertEquals(-0.5, LeastSquaresSlope.slope(List.of(1.0, 0.5)), 0.0);
  }

  @Test
  public void constantValuesHaveZeroSlope() {
    assertEquals(0.0, LeastSquaresSlope.slope(List.of(0.7, 0.7, 0.7)), 0.0);
  }

  @Test
  public void slopeOfScatteredValuesIsTheLeastSquaresFit() {
    // x̄ = 1.5, ȳ = 2.75, Σ dx·dy = 5.5, Σ dx² = 5.
    assertEquals(1.1, LeastSquaresSlope.slope(List.of(1.0, 3.0, 2.0, 5.0)), 0.0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void oneValueHasNoSlope() {
    LeastSquaresSlope.slope(List.of(1.0));
  }

  @Test
  public void seedsArePooledPerPrompt() {
    SeedReport a = seed(0, List.of(PromptCounts.of(0, 4, 12, 7), PromptCounts.of(1, 2, 6, 2)));
    SeedReport b = seed(1, List.of(PromptCounts.of(0, 3, 9, 4), PromptCounts.of(1, 2, 5, 1)));
    assertEquals(
        List.of(PromptCounts.of(0, 7, 21, 11), PromptCounts.of(1, 4, 11, 3)),
        new MetricCalculator().pooledPerPrompt(List.of(a, b)));
  }

  private static SeedReport seed(int seed, List<PromptCounts> prompts) {
    return SeedReport.builder()
        .seed(seed)
        .alpha(0)
        .tau(1)
        .alphaByPosition(List.of(OptionalDouble.empty()))
        .perPrompt(prompts)
        .positionCounts(List.of())
        .build();
  }
}
