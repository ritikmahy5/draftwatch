package dev.draftwatch.scoring;

import java.util.Objects;

/** A target name split into its family and run, as in {@code qwen-run2}. */
public final class TargetName {
  private final String family;
  private final Run run;

  TargetName(String family, Run run) {
    this.family = Objects.requireNonNull(family, "family");
    this.run = Objects.requireNonNull(run, "run");
  }

  public String family() {
    return family;
  }

  public Run run() {
    return run;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TargetName)) {
      return false;
    }
    TargetName other = (TargetName) o;
    return family.equals(other.family) && run == other.run;
  }

  @Override
  public int hashCode() {
    return Objects.hash(family, run);
  }

  @Override
  public String toString() {
    return family + "-" + run.suffix();
  }
}
