package dev.draftwatch.scoring;

import java.util.Objects;

/** A probe id split into its family and kind, as in {@code smol-code-live}. */
public final class ProbeName {
  private final String family;
  private final ProbeKind kind;

  ProbeName(String family, ProbeKind kind) {
    this.family = Objects.requireNonNull(family, "family");
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public String family() {
    return family;
  }

  public ProbeKind kind() {
    return kind;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProbeName)) {
      return false;
    }
    ProbeName other = (ProbeName) o;
    return family.equals(other.family) && kind == other.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(family, kind);
  }

  @Override
  public String toString() {
    return family + "-" + kind.suffix();
  }
}
