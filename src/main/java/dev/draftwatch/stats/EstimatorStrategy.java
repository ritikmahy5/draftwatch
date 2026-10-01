package dev.draftwatch.stats;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptCounts;
import java.util.List;
import java.util.OptionalDouble;

/**
 * How per-prompt counts become {@code alpha} and {@code tau} (MEASUREMENT_CONTRACT.md,
 * "Estimator"). Strategy: the probe's configured estimator selects the implementation, and both
 * the report parser and the detectors use the same one.
 */
public interface EstimatorStrategy {
  Estimator estimator();

  /** Acceptance rate; empty when undefined (nothing proposed in any counted prompt). */
  OptionalDouble alpha(List<PromptCounts> prompts);

  /** Mean accepted length; empty when undefined (no steps in any counted prompt). */
  OptionalDouble tau(List<PromptCounts> prompts);

  /** Prompts this estimator leaves out of {@code alpha}. */
  int excludedPrompts(List<PromptCounts> prompts);
}
