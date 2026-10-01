package dev.draftwatch.stats;

import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.PromptCounts;
import java.util.List;
import java.util.OptionalDouble;

/**
 * {@code simple_mean}: compute each metric per prompt, then take the unweighted mean over prompts
 * in index order. Prompts that proposed nothing are left out of {@code alpha}; prompts with no
 * steps are left out of {@code tau}.
 */
final class SimpleMeanEstimator implements EstimatorStrategy {
  @Override
  public Estimator estimator() {
    return Estimator.SIMPLE_MEAN;
  }

  @Override
  public OptionalDouble alpha(List<PromptCounts> prompts) {
    double sum = 0;
    int counted = 0;
    for (PromptCounts p : prompts) {
      if (p.proposed() > 0) {
        sum += (double) p.accepted() / (double) p.proposed();
        counted++;
      }
    }
    return counted == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / counted);
  }

  @Override
  public OptionalDouble tau(List<PromptCounts> prompts) {
    double sum = 0;
    int counted = 0;
    for (PromptCounts p : prompts) {
      if (p.steps() > 0) {
        sum += (double) (p.accepted() + p.steps()) / (double) p.steps();
        counted++;
      }
    }
    return counted == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / counted);
  }

  @Override
  public int excludedPrompts(List<PromptCounts> prompts) {
    int excluded = 0;
    for (PromptCounts p : prompts) {
      if (p.proposed() == 0) {
        excluded++;
      }
    }
    return excluded;
  }
}
