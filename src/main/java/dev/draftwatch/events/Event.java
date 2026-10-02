package dev.draftwatch.events;

import java.time.Instant;

/** Something that happened, published on the {@link EventBus}. Events are immutable. */
public interface Event {
  Instant at();
}
