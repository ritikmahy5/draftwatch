package dev.draftwatch.domain;

import java.util.Optional;
import java.util.StringJoiner;

/**
 * An enum constant that has an exact external spelling, used in config files and harness
 * reports (for example {@code token_weighted}).
 */
public interface WireNamed {
  /** The spelling used in config files and harness reports. */
  String wireName();

  /** Returns the constant of {@code type} whose wire name is exactly {@code name}. */
  static <E extends Enum<E> & WireNamed> Optional<E> parse(Class<E> type, String name) {
    for (E constant : type.getEnumConstants()) {
      if (constant.wireName().equals(name)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }

  /** All wire names of {@code type} in declaration order, for error messages: {@code a | b}. */
  static <E extends Enum<E> & WireNamed> String allNames(Class<E> type) {
    StringJoiner names = new StringJoiner(" | ");
    for (E constant : type.getEnumConstants()) {
      names.add(constant.wireName());
    }
    return names.toString();
  }
}
