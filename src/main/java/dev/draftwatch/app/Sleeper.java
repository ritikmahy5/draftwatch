package dev.draftwatch.app;

import java.time.Duration;

/** Waits between polls. An interface so tests do not wait. */
public interface Sleeper {
  void sleep(Duration duration) throws InterruptedException;

  /** {@link Thread#sleep}. */
  static Sleeper system() {
    return duration -> Thread.sleep(duration.toMillis());
  }
}
