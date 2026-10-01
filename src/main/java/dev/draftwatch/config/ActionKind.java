package dev.draftwatch.config;

import dev.draftwatch.domain.WireNamed;

/**
 * {@code on_regression} actions (SPEC.md F6). {@code retrain_draft} is added in M7 together with
 * its configuration; until then it is rejected as unknown rather than silently ignored.
 */
public enum ActionKind implements WireNamed {
  NOTIFY("notify");

  private final String wireName;

  ActionKind(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
