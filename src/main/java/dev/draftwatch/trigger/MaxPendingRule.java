package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;

/** {@code max_pending(k)}: Reject if ≥ k jobs for this target are not terminal; else Abstain. */
public final class MaxPendingRule implements TriggerRule {
  private final int limit;

  public MaxPendingRule(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("max_pending must be >= 1, was " + limit);
    }
    this.limit = limit;
  }

  @Override
  public String describe() {
    return "max_pending(" + limit + ")";
  }

  @Override
  public TriggerDecision evaluate(Checkpoint checkpoint, ResolvedProbe probe, History history) {
    int active = history.activeJobs(checkpoint.targetName());
    return active >= limit
        ? TriggerDecision.reject(active + " jobs of this target are active (limit " + limit + ")")
        : TriggerDecision.abstain();
  }
}
