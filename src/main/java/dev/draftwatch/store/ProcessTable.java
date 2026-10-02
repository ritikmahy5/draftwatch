package dev.draftwatch.store;

import java.time.Instant;
import java.util.Optional;

/** Whether a process on this host is still running. An interface so tests can fake liveness. */
public interface ProcessTable {
  /**
   * True if a process with {@code pid} is alive and, when {@code start} is known for both it and
   * the live process, started at {@code start}. A PID reused by a newer process is not alive.
   */
  boolean isAlive(long pid, Optional<Instant> start);
}
