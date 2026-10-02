package dev.draftwatch.discovery;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides whether a checkpoint directory is completely written (SPEC.md F1, "complete").
 * Strategy: the target's {@code completion} config selects the implementation.
 */
public interface CompletionPolicy {
  /** Empty if {@code dir} is complete at {@code now}; otherwise why it is not yet. */
  Optional<String> incompleteReason(Path dir, Instant now);
}
