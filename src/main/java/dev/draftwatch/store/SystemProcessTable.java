package dev.draftwatch.store;

import java.time.Instant;
import java.util.Optional;

/** Liveness from the operating system's process table, via {@link ProcessHandle}. */
public final class SystemProcessTable implements ProcessTable {
  @Override
  public boolean isAlive(long pid, Optional<Instant> start) {
    return ProcessHandle.of(pid)
        .filter(ProcessHandle::isAlive)
        .filter(
            p -> {
              Optional<Instant> current = p.info().startInstant();
              return start.isEmpty() || current.isEmpty() || start.equals(current);
            })
        .isPresent();
  }
}
