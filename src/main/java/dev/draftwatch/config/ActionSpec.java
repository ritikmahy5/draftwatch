package dev.draftwatch.config;

import java.util.Objects;
import java.util.Optional;

/** One {@code on_regression} entry: its kind, and its settings if it has any. */
public final class ActionSpec {
  private final ActionKind kind;
  private final Optional<RetrainSpec> retrain;

  private ActionSpec(ActionKind kind, Optional<RetrainSpec> retrain) {
    this.kind = kind;
    this.retrain = retrain;
  }

  public static ActionSpec notifyAction() {
    return new ActionSpec(ActionKind.NOTIFY, Optional.empty());
  }

  public static ActionSpec retrainDraft(RetrainSpec spec) {
    return new ActionSpec(
        ActionKind.RETRAIN_DRAFT, Optional.of(Objects.requireNonNull(spec, "spec")));
  }

  public ActionKind kind() {
    return kind;
  }

  /** Present exactly for {@link ActionKind#RETRAIN_DRAFT}. */
  public Optional<RetrainSpec> retrain() {
    return retrain;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ActionSpec)) {
      return false;
    }
    ActionSpec that = (ActionSpec) o;
    return kind == that.kind && retrain.equals(that.retrain);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, retrain);
  }

  @Override
  public String toString() {
    return kind.wireName()
        + retrain.map(r -> " (command: " + String.join(" ", r.command()) + ")").orElse("");
  }
}
