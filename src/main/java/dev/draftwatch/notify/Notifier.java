package dev.draftwatch.notify;

import dev.draftwatch.events.DetectionEvent;

/**
 * Delivers an alert about a detection outcome (Strategy: console and log file in v1; network
 * notifiers are out of scope, DECISIONS.md D12).
 */
public interface Notifier {
  String name();

  void notify(DetectionEvent event) throws Exception;
}
