package dev.draftwatch.events;

/** Receives events of one type from the {@link EventBus}. */
@FunctionalInterface
public interface Subscriber<E extends Event> {
  /** Handles {@code event}; anything thrown is caught and reported by the bus. */
  void on(E event) throws Exception;
}
