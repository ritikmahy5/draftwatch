package dev.draftwatch.discovery;

/** Where a target's checkpoints appear (Strategy: directories in v1). */
public interface CheckpointSource {
  /** One look at the source: the complete checkpoints now, and what was skipped and why. */
  Discovery poll();
}
