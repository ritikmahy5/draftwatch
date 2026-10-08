package dev.draftwatch.stats;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PositionCount;
import dev.draftwatch.domain.PromptCounts;
import dev.draftwatch.domain.SeedReport;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The one implementation of every metric in MEASUREMENT_CONTRACT.md, "Metric definitions". The
 * report parser uses it to check the harness's numbers; detectors use it to recompute metrics on
 * bootstrap resamples. It never rounds.
 *
 * <p>It registers the package-private {@link EstimatorStrategy} implementations itself rather than
 * receiving them from {@code Bootstrap}: the set is closed by the contract's {@link Estimator}
 * enum, and the constructor fails if an estimator has no strategy.
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

  /**
   * Per-prompt counts summed over seeds (ARCHITECTURE.md, "Statistics": with several seeds the
   * bootstrap resamples prompts of the pooled counts).
   *
   * @throws IllegalArgumentException if the seeds have different prompt counts
   */
  public List<PromptCounts> pooledPerPrompt(List<SeedReport> seeds) {
    if (seeds.isEmpty()) {
      throw new IllegalArgumentException("no seeds to pool");
    }
    int n = seeds.get(0).perPrompt().size();
    long[] steps = new long[n];
    long[] proposed = new long[n];
    long[] accepted = new long[n];
    for (SeedReport seed : seeds) {
      if (seed.perPrompt().size() != n) {
        throw new IllegalArgumentException(
            "seed " + seed.seed() + " has " + seed.perPrompt().size() + " prompts, expected " + n);
      }
      for (int i = 0; i < n; i++) {
        PromptCounts p = seed.perPrompt().get(i);
        steps[i] += p.steps();
        proposed[i] += p.proposed();
        accepted[i] += p.accepted();
      }
    }
    List<PromptCounts> pooled = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      pooled.add(PromptCounts.of(i, steps[i], proposed[i], accepted[i]));
    }
    return pooled;
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
