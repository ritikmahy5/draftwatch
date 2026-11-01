package dev.draftwatch.notify;

/**
 * Delivers one alert line, already formatted by {@link AlertFormat} (Strategy: console and log
 * file in v1; network notifiers are out of scope).
 */
public interface Notifier {
  String name();

  void notify(String alertLine) throws Exception;
}
