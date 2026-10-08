package dev.draftwatch.config;

import java.util.List;
import java.util.Objects;

/**
 * {@code harness}: the command that runs the measurement harness. The engine appends the
 * contract's arguments (MEASUREMENT_CONTRACT.md, "Invocation"). The command is used verbatim;
 * relative elements are interpreted by the executor relative to the config file's directory.
 */
public final class HarnessConfig {
  private final List<String> command;

  private HarnessConfig(List<String> command) {
    this.command = command;
  }

  /** @throws IllegalArgumentException if {@code command} is empty */
  public static HarnessConfig of(List<String> command) {
    List<String> copy = List.copyOf(command);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("harness command must not be empty");
    }
    return new HarnessConfig(copy);
  }

  public List<String> command() {
    return command;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof HarnessConfig && command.equals(((HarnessConfig) o).command);
  }

  @Override
  public int hashCode() {
    return Objects.hash(command);
  }
}
