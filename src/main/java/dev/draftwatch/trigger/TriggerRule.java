package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;

/**
 * Decides whether a discovered checkpoint is measured with a probe (SPEC.md F2). Takes the
 * resolved probe because {@code not_already_measured} needs the probe hash (DECISIONS.md D57).
 * Each rule is one handler of {@link TriggerChain}'s Chain of Responsibility: it accepts,
 * rejects, or abstains so that the next rule decides.
 */
public interface TriggerRule {
  /** The rule as configured, for example {@code every_n_steps(500)}. */
  String describe();

  TriggerDecision evaluate(Checkpoint checkpoint, ResolvedProbe probe, History history);
}
