package dev.draftwatch.events;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Delivers events to subscribers (Observer: stages and their consumers never call each other
 * directly). Created once by {@code Bootstrap} and injected; never accessed statically.
 *
 * <p>Delivery is synchronous, in subscription order, to every subscriber registered for the
 * event's class or a supertype. A subscriber that throws does not stop delivery to the others:
 * the exception is written to {@code diagnostics} and published as
 * {@link SubscriberFailed}. A failure while delivering {@code SubscriberFailed} is only written
 * to {@code diagnostics}, so failures cannot recurse.
 */
public final class EventBus {
  private final List<Registration<?>> registrations = new ArrayList<>();
  private final Consumer<String> diagnostics;
  private final Clock clock;

  /**
   * Creates a bus.
   *
   * @param diagnostics where subscriber failures are written, for example standard error
   */
  public EventBus(Consumer<String> diagnostics, Clock clock) {
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static final class Registration<E extends Event> {
    private final Class<E> type;
    private final String name;
    private final Subscriber<? super E> subscriber;

    Registration(Class<E> type, String name, Subscriber<? super E> subscriber) {
      this.type = type;
      this.name = name;
      this.subscriber = subscriber;
    }

    boolean accepts(Event event) {
      return type.isInstance(event);
    }

    void deliver(Event event) throws Exception {
      subscriber.on(type.cast(event));
    }
  }

  /**
   * Subscribes {@code subscriber} to events of {@code type} and its subtypes.
   *
   * @param name identifies the subscriber in failure reports
   */
  public <E extends Event> void subscribe(
      Class<E> type, String name, Subscriber<? super E> subscriber) {
    registrations.add(
        new Registration<>(
            Objects.requireNonNull(type, "type"),
            Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(subscriber, "subscriber")));
  }

  /** Delivers {@code event} to every matching subscriber; never throws a subscriber's failure. */
  public void publish(Event event) {
    Objects.requireNonNull(event, "event");
    for (Registration<?> r : List.copyOf(registrations)) {
      if (!r.accepts(event)) {
        continue;
      }
      try {
        r.deliver(event);
      } catch (Exception e) {
        String message = "subscriber " + r.name + " failed on " + event + ": " + e;
        diagnostics.accept("draftwatch: " + message);
        if (!(event instanceof SubscriberFailed)) {
          publish(new SubscriberFailed(clock.instant(), r.name, event.toString(), e.toString()));
        }
      }
    }
  }
}
