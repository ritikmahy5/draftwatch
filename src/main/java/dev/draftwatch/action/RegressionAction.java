package dev.draftwatch.action;

import dev.draftwatch.events.RegressionDetected;

/**
 * Something done in response to a regression (Command: actions are configured per target as
 * data, {@code on_regression}, and executed later when a regression is detected).
 */
public interface RegressionAction {
  /** The config name, for example {@code notify}. */
  String name();

  void execute(RegressionDetected event) throws Exception;
}
