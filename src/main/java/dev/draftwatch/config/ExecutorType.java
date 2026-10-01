package dev.draftwatch.config;

import dev.draftwatch.domain.WireNamed;

/** {@code executor.type}: where measurement jobs run (SPEC.md F3). */
public enum ExecutorType implements WireNamed {
  LOCAL("local"),
  SLURM("slurm");

  private final String wireName;

  ExecutorType(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
