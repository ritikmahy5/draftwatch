package dev.draftwatch.store;

import dev.draftwatch.domain.Baseline;
import java.util.Optional;

/** Each target's baseline checkpoint (Repository; ARCHITECTURE.md, "Persistence"). */
public interface BaselineRepository {
  Optional<Baseline> get(String target);

  /** Sets or replaces {@code baseline.targetName()}'s baseline. */
  void set(Baseline baseline);
}
