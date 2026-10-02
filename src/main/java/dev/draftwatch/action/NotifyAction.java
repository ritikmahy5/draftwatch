package dev.draftwatch.action;

import dev.draftwatch.events.RegressionDetected;
import dev.draftwatch.notify.Notifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code on_regression: [notify]}: sends the regression to every notifier. Every notifier is
 * tried even if an earlier one fails; the failures are then reported together.
 */
public final class NotifyAction implements RegressionAction {
  private final List<Notifier> notifiers;

  public NotifyAction(List<Notifier> notifiers) {
    this.notifiers = List.copyOf(Objects.requireNonNull(notifiers, "notifiers"));
  }

  @Override
  public String name() {
    return "notify";
  }

  /** @throws ActionFailedException naming every notifier that failed */
  @Override
  public void execute(RegressionDetected event) {
    List<String> failures = new ArrayList<>();
    for (Notifier notifier : notifiers) {
      try {
        notifier.notify(event);
      } catch (Exception e) {
        failures.add(notifier.name() + ": " + e);
      }
    }
    if (!failures.isEmpty()) {
      throw new ActionFailedException("notify failed for " + String.join("; ", failures));
    }
  }
}
