package dev.draftwatch.domain;

/** Whether a checkpoint holds full model weights or an adapter that needs a base model. */
public enum CheckpointType implements WireNamed {
  FULL("full"),
  /** Adapter weights; the harness merges them into the target's base model (DECISIONS.md D8). */
  ADAPTER("adapter");

  private final String wireName;

  CheckpointType(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
