package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;

/** {@code always_final}: Accept if the checkpoint is final; else Abstain (SPEC.md F2). */
public final class AlwaysFinalRule implements TriggerRule {
  @Override
  public String describe() {
    return "always_final";
  }

  @Override
  public TriggerDecision evaluate(Checkpoint checkpoint, ResolvedProbe probe, History history) {
    return checkpoint.isFinal()
        ? TriggerDecision.accept("final checkpoint")
        : TriggerDecision.abstain();
  }
}
