package dev.draftwatch.stats;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptCounts;
import java.util.List;
import java.util.OptionalDouble;

/**
 * {@code token_weighted}: pool every step of every prompt and compute each ratio once:
 * {@code alpha = Σ accepted / Σ proposed} and {@code tau = (Σ accepted + Σ steps) / Σ steps}.
 * Sums are exact integers, so the only rounding is the final division.
 */
final class TokenWeightedEstimator implements EstimatorStrategy {
  @Override
  public Estimator estimator() {
    return Estimator.TOKEN_WEIGHTED;
  }

  @Override
  public OptionalDouble alpha(List<PromptCounts> prompts) {
    long accepted = 0;
    long proposed = 0;
    for (PromptCounts p : prompts) {
      accepted += p.accepted();
      proposed += p.proposed();
    }
    return proposed == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((double) accepted / (double) proposed);
  }

  @Override
  public OptionalDouble tau(List<PromptCounts> prompts) {
    long accepted = 0;
    long steps = 0;
    for (PromptCounts p : prompts) {
      accepted += p.accepted();
      steps += p.steps();
    }
    return steps == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((double) (accepted + steps) / (double) steps);
  }

  /** Pooling excludes no prompt. */
  @Override
  public int excludedPrompts(List<PromptCounts> prompts) {
    return 0;
  }
}
