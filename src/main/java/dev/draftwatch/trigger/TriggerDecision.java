package dev.draftwatch.trigger;

import java.util.Objects;
import java.util.Optional;

/** One rule's answer: Accept, Reject with a reason, or Abstain (SPEC.md F2). */
public final class TriggerDecision {
  /** The three answers a rule can give. */
  public enum Kind {
    ACCEPT,
    REJECT,
    ABSTAIN
  }

  private static final TriggerDecision ABSTAIN =
      new TriggerDecision(Kind.ABSTAIN, Optional.empty());

  private final Kind kind;
  private final Optional<String> reason;

  private TriggerDecision(Kind kind, Optional<String> reason) {
    this.kind = kind;
    this.reason = reason;
  }

  public static TriggerDecision accept(String reason) {
    return new TriggerDecision(Kind.ACCEPT, Optional.of(Objects.requireNonNull(reason, "reason")));
  }

  public static TriggerDecision reject(String reason) {
    return new TriggerDecision(Kind.REJECT, Optional.of(Objects.requireNonNull(reason, "reason")));
  }

  public static TriggerDecision abstain() {
    return ABSTAIN;
  }

  public Kind kind() {
    return kind;
  }

  /** Why the rule accepted or rejected; empty when it abstained. */
  public Optional<String> reason() {
    return reason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TriggerDecision)) {
      return false;
    }
    TriggerDecision that = (TriggerDecision) o;
    return kind == that.kind && reason.equals(that.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, reason);
  }

  @Override
  public String toString() {
    return kind + reason.map(r -> ": " + r).orElse("");
  }
}
