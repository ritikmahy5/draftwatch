package dev.draftwatch.events;

import java.time.Instant;
import java.util.Objects;

/** A subscriber threw while handling an event; delivery to the others continued. */
public final class SubscriberFailed implements Event {
  private final Instant at;
  private final String subscriber;
  private final String event;
  private final String error;

  public SubscriberFailed(Instant at, String subscriber, String event, String error) {
    this.at = Objects.requireNonNull(at, "at");
    this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
    this.event = Objects.requireNonNull(event, "event");
    this.error = Objects.requireNonNull(error, "error");
  }

  @Override
  public Instant at() {
    return at;
  }

  public String subscriber() {
    return subscriber;
  }

  /** The failed event, as text. */
  public String event() {
    return event;
  }

  public String error() {
    return error;
  }

  @Override
  public String toString() {
    return "SubscriberFailed{" + subscriber + ": " + error + "}";
  }
}
