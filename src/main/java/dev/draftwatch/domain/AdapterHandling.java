package dev.draftwatch.domain;

/** How the harness treated the checkpoint, as declared in the report's {@code adapter_handling}. */
public enum AdapterHandling implements WireNamed {
  /** Full checkpoint; nothing was merged. */
  NONE("none"),
  /** Adapter merged into the base model before measurement. */
  MERGED("merged");

  private final String wireName;

  AdapterHandling(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
