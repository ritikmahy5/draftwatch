package dev.draftwatch.scoring;

/**
 * Parses the evaluation's target names ({@code <family>-run1}, {@code <family>-run2}) and probe
 * ids ({@code <family>-chat}, {@code -code}, {@code -chat-live}, {@code -code-live}).
 */
public final class EvaluationNames {
  private EvaluationNames() {}

  /**
   * The family and run of {@code name}.
   *
   * @throws IllegalArgumentException if it does not end in {@code -run1} or {@code -run2} after a
   *     non-empty family
   */
  public static TargetName target(String name) {
    for (Run run : Run.values()) {
      String family = familyBefore(name, "-" + run.suffix());
      if (family != null) {
        return new TargetName(family, run);
      }
    }
    throw new IllegalArgumentException("unknown target name: " + name);
  }

  /**
   * The family and kind of {@code id}; the longest matching suffix wins, so {@code -chat-live} is
   * never read as {@code -chat}.
   *
   * @throws IllegalArgumentException if no probe suffix follows a non-empty family
   */
  public static ProbeName probe(String id) {
    ProbeKind best = null;
    String bestFamily = null;
    for (ProbeKind kind : ProbeKind.values()) {
      String family = familyBefore(id, "-" + kind.suffix());
      if (family != null && (best == null || kind.suffix().length() > best.suffix().length())) {
        best = kind;
        bestFamily = family;
      }
    }
    if (best == null) {
      throw new IllegalArgumentException("unknown probe id: " + id);
    }
    return new ProbeName(bestFamily, best);
  }

  private static String familyBefore(String name, String suffix) {
    if (name == null || !name.endsWith(suffix) || name.length() == suffix.length()) {
      return null;
    }
    return name.substring(0, name.length() - suffix.length());
  }
}
