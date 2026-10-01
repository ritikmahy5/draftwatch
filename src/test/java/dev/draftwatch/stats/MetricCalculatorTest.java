package dev.draftwatch.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.Test;

/**
 * Exact-output tests. Counts are invented; every expected value is written as the fraction it is
 * defined to be and compared with zero tolerance.
 */
public class MetricCalculatorTest {
  private final MetricCalculator calc = new MetricCalculator();

  /** steps / proposed / accepted per prompt; prompt 2 proposed nothing. */
  private static final List<PromptCounts> PROMPTS =
      List.of(
          PromptCounts.of(0, 4, 12, 7),
          PromptCounts.of(1, 2, 6, 2),
          PromptCounts.of(2, 1, 0, 0),
          PromptCounts.of(3, 3, 8, 3));

  @Test
  public void tokenWeightedPoolsAllPrompts() {
    // accepted 12, proposed 26, steps 10
    assertEquals(OptionalDouble.of(12.0 / 26.0), calc.alpha(Estimator.TOKEN_WEIGHTED, PROMPTS));
    assertEquals(
        OptionalDouble.of((12.0 + 10.0) / 10.0), calc.tau(Estimator.TOKEN_WEIGHTED, PROMPTS));
    assertEquals(0, calc.excludedPrompts(Estimator.TOKEN_WEIGHTED, PROMPTS));
  }

  @Test
  public void simpleMeanAveragesPerPromptRatiosInIndexOrder() {
    double alpha = ((7.0 / 12.0 + 2.0 / 6.0) + 3.0 / 8.0) / 3;
    double tau = (((7.0 + 4.0) / 4.0 + (2.0 + 2.0) / 2.0) + (0.0 + 1.0) / 1.0 + (3.0 + 3.0) / 3.0);
    assertEquals(OptionalDouble.of(alpha), calc.alpha(Estimator.SIMPLE_MEAN, PROMPTS));
    assertEquals(OptionalDouble.of(tau / 4), calc.tau(Estimator.SIMPLE_MEAN, PROMPTS));
    assertEquals(1, calc.excludedPrompts(Estimator.SIMPLE_MEAN, PROMPTS));
  }

  @Test
  public void estimatorsDifferWhenPromptLengthsVary() {
    double tokenWeighted = calc.alpha(Estimator.TOKEN_WEIGHTED, PROMPTS).getAsDouble();
    double simpleMean = calc.alpha(Estimator.SIMPLE_MEAN, PROMPTS).getAsDouble();
    assertNotEquals(tokenWeighted, simpleMean, 0.0);
  }

  @Test
  public void metricsAreUndefinedWithNothingToAverage() {
    List<PromptCounts> nothing = List.of(PromptCounts.of(0, 0, 0, 0), PromptCounts.of(1, 0, 0, 0));
    for (Estimator e : Estimator.values()) {
      assertEquals(e.toString(), OptionalDouble.empty(), calc.alpha(e, nothing));
      assertEquals(e.toString(), OptionalDouble.empty(), calc.tau(e, nothing));
    }
    List<PromptCounts> stepsButNoDrafts = List.of(PromptCounts.of(0, 3, 0, 0));
    assertEquals(OptionalDouble.empty(), calc.alpha(Estimator.TOKEN_WEIGHTED, stepsButNoDrafts));
    assertEquals(OptionalDouble.of(1.0), calc.tau(Estimator.TOKEN_WEIGHTED, stepsButNoDrafts));
  }

  @Test
  public void alphaByPositionIsPooledAndEmptyWhereNothingWasEligible() {
    List<PositionCount> positions =
        List.of(PositionCount.of(1, 9, 6), PositionCount.of(2, 6, 4), PositionCount.of(3, 0, 0));
    assertEquals(
        List.of(OptionalDouble.of(6.0 / 9.0), OptionalDouble.of(4.0 / 6.0), OptionalDouble.empty()),
        calc.alphaByPosition(positions));
  }

  @Test
  public void seedAggregatesUseSampleStandardDeviation() {
    assertEquals(2.0, calc.mean(List.of(1.0, 2.0, 3.0)), 0.0);
    assertEquals(OptionalDouble.of(1.0), calc.sampleStd(List.of(1.0, 2.0, 3.0)));
    assertEquals(OptionalDouble.of(Math.sqrt(0.5)), calc.sampleStd(List.of(1.0, 2.0)));
    assertEquals(OptionalDouble.empty(), calc.sampleStd(List.of(0.4)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void meanOfNothingIsRejected() {
    calc.mean(List.of());
  }

  @Test
  public void everyEstimatorHasAStrategy() {
    for (Estimator e : Estimator.values()) {
      assertEquals(e, calc.strategy(e).estimator());
    }
  }
}
