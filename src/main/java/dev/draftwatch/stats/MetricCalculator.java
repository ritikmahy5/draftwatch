package dev.draftwatch.stats;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The one implementation of every metric in MEASUREMENT_CONTRACT.md, "Metric definitions". The
 * report parser uses it to check the harness's numbers; detectors use it to recompute metrics on
 * bootstrap resamples. It never rounds.
 */
public final class MetricCalculator {
  private final Map<Estimator, EstimatorStrategy> strategies = new EnumMap<>(Estimator.class);

  public MetricCalculator() {
    register(new TokenWeightedEstimator());
    register(new SimpleMeanEstimator());
    for (Estimator e : Estimator.values()) {
      if (!strategies.containsKey(e)) {
        throw new IllegalStateException("no strategy for estimator " + e);
      }
    }
  }

  private void register(EstimatorStrategy strategy) {
    strategies.put(strategy.estimator(), strategy);
  }

  public EstimatorStrategy strategy(Estimator estimator) {
    return strategies.get(estimator);
  }

  public OptionalDouble alpha(Estimator estimator, List<PromptCounts> prompts) {
    return strategy(estimator).alpha(prompts);
  }

  public OptionalDouble tau(Estimator estimator, List<PromptCounts> prompts) {
    return strategy(estimator).tau(prompts);
  }

  public int excludedPrompts(Estimator estimator, List<PromptCounts> prompts) {
    return strategy(estimator).excludedPrompts(prompts);
  }

  /**
   * Positional acceptance, pooled across prompts regardless of estimator: for each position,
   * {@code accepted / eligible}, empty where nothing was eligible.
   */
  public List<OptionalDouble> alphaByPosition(List<PositionCount> positions) {
    List<OptionalDouble> out = new ArrayList<>(positions.size());
    for (PositionCount p : positions) {
      out.add(
          p.eligible() == 0
              ? OptionalDouble.empty()
              : OptionalDouble.of((double) p.accepted() / (double) p.eligible()));
    }
    return out;
  }

  /** Arithmetic mean, summing in list order. */
  public double mean(List<Double> values) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException("mean of no values");
    }
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.size();
  }

  /**
   * Sample standard deviation (n − 1 denominator), two-pass; empty for a single value, as the
   * contract's {@code *_std} is {@code null} with one seed.
   */
  public OptionalDouble sampleStd(List<Double> values) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException("standard deviation of no values");
    }
    if (values.size() == 1) {
      return OptionalDouble.empty();
    }
    double mean = mean(values);
    double squares = 0;
    for (double v : values) {
      squares += (v - mean) * (v - mean);
    }
    return OptionalDouble.of(Math.sqrt(squares / (values.size() - 1)));
  }
}
