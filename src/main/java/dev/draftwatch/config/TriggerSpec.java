package dev.draftwatch.config;

import dev.draftwatch.domain.WireNamed;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One configured trigger rule (SPEC.md F2): its kind and, for {@code max_pending} and
 * {@code every_n_steps}, a positive integer argument. The rule objects themselves are built from
 * these specs by {@code Bootstrap}.
 */
public final class TriggerSpec {
  /** The trigger rules of SPEC.md F2. */
  public enum Kind implements WireNamed {
    NOT_ALREADY_MEASURED("not_already_measured", false),
    MAX_PENDING("max_pending", true),
    ALWAYS_FINAL("always_final", false),
    EVERY_N_STEPS("every_n_steps", true);

    private final String wireName;
    private final boolean takesArgument;

    Kind(String wireName, boolean takesArgument) {
      this.wireName = wireName;
      this.takesArgument = takesArgument;
    }

    @Override
    public String wireName() {
      return wireName;
    }

    /** True if the rule is configured with an integer ({@code max_pending: 4}). */
    public boolean takesArgument() {
      return takesArgument;
    }
  }

  private final Kind kind;
  private final OptionalInt argument;

  private TriggerSpec(Kind kind, OptionalInt argument) {
    this.kind = kind;
    this.argument = argument;
  }

  /** A rule without an argument. */
  public static TriggerSpec of(Kind kind) {
    Objects.requireNonNull(kind, "kind");
    if (kind.takesArgument()) {
      throw new IllegalArgumentException(kind.wireName() + " needs an integer argument");
    }
    return new TriggerSpec(kind, OptionalInt.empty());
  }

  /** A rule with a positive integer argument. */
  public static TriggerSpec of(Kind kind, int argument) {
    Objects.requireNonNull(kind, "kind");
    if (!kind.takesArgument()) {
      throw new IllegalArgumentException(kind.wireName() + " takes no argument");
    }
    if (argument <= 0) {
      throw new IllegalArgumentException(kind.wireName() + " must be > 0, was " + argument);
    }
    return new TriggerSpec(kind, OptionalInt.of(argument));
  }

  /**
   * SPEC.md F2: the chain used when {@code triggers} is omitted:
   * {@code not_already_measured, max_pending(4), always_final, every_n_steps(1)}.
   */
  public static List<TriggerSpec> defaultChain() {
    return List.of(
        of(Kind.NOT_ALREADY_MEASURED),
        of(Kind.MAX_PENDING, 4),
        of(Kind.ALWAYS_FINAL),
        of(Kind.EVERY_N_STEPS, 1));
  }

  public Kind kind() {
    return kind;
  }

  /** Present exactly when {@link Kind#takesArgument()}. */
  public OptionalInt argument() {
    return argument;
  }

  /** For example {@code max_pending(4)} or {@code always_final}. */
  public String describe() {
    return argument.isPresent()
        ? kind.wireName() + "(" + argument.getAsInt() + ")"
        : kind.wireName();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TriggerSpec)) {
      return false;
    }
    TriggerSpec that = (TriggerSpec) o;
    return kind == that.kind && argument.equals(that.argument);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, argument);
  }

  @Override
  public String toString() {
    return describe();
  }
}
