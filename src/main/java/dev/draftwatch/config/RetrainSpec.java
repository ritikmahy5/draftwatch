package dev.draftwatch.config;

import java.util.List;
import java.util.Objects;

/** {@code retrain_draft: { command: [...] }}: the training command (DECISIONS.md D75). */
public final class RetrainSpec {
  private final List<String> command;

  private RetrainSpec(List<String> command) {
    this.command = command;
  }

  /** @throws IllegalArgumentException if {@code command} is empty */
  public static RetrainSpec of(List<String> command) {
    List<String> copy = List.copyOf(Objects.requireNonNull(command, "command"));
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    return new RetrainSpec(copy);
  }

  /** Run verbatim, like {@code harness.command}. */
  public List<String> command() {
    return command;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RetrainSpec && command.equals(((RetrainSpec) o).command);
  }

  @Override
  public int hashCode() {
    return command.hashCode();
  }

  @Override
  public String toString() {
    return "retrain_draft (command: " + String.join(" ", command) + ")";
  }
}
