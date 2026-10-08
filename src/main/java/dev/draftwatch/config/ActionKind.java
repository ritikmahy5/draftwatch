package dev.draftwatch.config;

import dev.draftwatch.domain.WireNamed;

/** {@code on_regression} actions (SPEC.md F6). */
public enum ActionKind implements WireNamed {
  NOTIFY("notify"),
  RETRAIN_DRAFT("retrain_draft");

  private final String wireName;

  ActionKind(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
