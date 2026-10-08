package dev.draftwatch.testing;

import dev.draftwatch.discovery.CheckpointSource;
import dev.draftwatch.discovery.Discovery;
import dev.draftwatch.domain.Checkpoint;
import java.util.List;

/**
 * A checkpoint source whose discovery is set by the test: every poll returns the current one and
 * is counted. No checkpoint directory is involved.
 */
public final class ScriptedCheckpointSource implements CheckpointSource {
  private Discovery current = new Discovery(List.of(), List.of());
  private int polls;

  public ScriptedCheckpointSource returns(
      List<Checkpoint> checkpoints, List<Discovery.Skipped> skipped) {
    current = new Discovery(checkpoints, skipped);
    return this;
  }

  public int polls() {
    return polls;
  }

  @Override
  public Discovery poll() {
    polls++;
    return current;
  }
}
