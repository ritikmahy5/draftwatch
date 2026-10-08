package dev.draftwatch.domain;

/**
 * Shape of the draft proposals in one verification step. v1 supports chain drafting only;
 * a tree structure would change what "proposed" and "position" mean.
 */
public enum DraftStructure implements WireNamed {
  CHAIN("chain");

  private final String wireName;

  DraftStructure(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
