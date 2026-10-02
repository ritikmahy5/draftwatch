package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates a target's trigger rules in configured order (Chain of Responsibility): the first rule
 * that does not abstain decides, with its reason; if every rule abstains, the checkpoint is
 * accepted (SPEC.md F2).
 */
public final class TriggerChain {
  private final List<TriggerRule> rules;

  public TriggerChain(List<TriggerRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<TriggerRule> rules() {
    return rules;
  }

  /** The chain's outcome: accepted or not, and which rule decided why. */
  public static final class Outcome {
    private final boolean accepted;
    private final Optional<String> rule;
    private final String reason;

    private Outcome(boolean accepted, Optional<String> rule, String reason) {
      this.accepted = accepted;
      this.rule = rule;
      this.reason = reason;
    }

    public boolean accepted() {
      return accepted;
    }

    /** The deciding rule; empty when every rule abstained. */
    public Optional<String> rule() {
      return rule;
    }

    public String reason() {
      return reason;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Outcome)) {
        return false;
      }
      Outcome that = (Outcome) o;
      return accepted == that.accepted && rule.equals(that.rule) && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
      return Objects.hash(accepted, rule, reason);
    }

    @Override
    public String toString() {
      return (accepted ? "accepted" : "rejected") + rule.map(r -> " by " + r).orElse("") + ": "
          + reason;
    }
  }

  public Outcome decide(Checkpoint checkpoint, ResolvedProbe probe, History history) {
    for (TriggerRule rule : rules) {
      TriggerDecision d = rule.evaluate(checkpoint, probe, history);
      if (d.kind() != TriggerDecision.Kind.ABSTAIN) {
        return new Outcome(
            d.kind() == TriggerDecision.Kind.ACCEPT,
            Optional.of(rule.describe()),
            d.reason().orElse(""));
      }
    }
    return new Outcome(true, Optional.empty(), "every rule abstained");
  }
}
