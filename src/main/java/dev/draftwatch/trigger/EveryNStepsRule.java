package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;

/** {@code every_n_steps(n)}: Abstain if step % n == 0; else Reject (SPEC.md F2). */
public final class EveryNStepsRule implements TriggerRule {
  private final long n;

  public EveryNStepsRule(long n) {
    if (n < 1) {
      throw new IllegalArgumentException("every_n_steps must be >= 1, was " + n);
    }
    this.n = n;
  }

  @Override
  public String describe() {
    return "every_n_steps(" + n + ")";
  }

  @Override
  public TriggerDecision evaluate(Checkpoint checkpoint, ResolvedProbe probe, History history) {
    return checkpoint.step() % n == 0
        ? TriggerDecision.abstain()
        : TriggerDecision.reject("step " + checkpoint.step() + " is not a multiple of " + n);
  }
}
