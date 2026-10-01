package dev.draftwatch.domain;

import java.util.List;
import java.util.Objects;

/** Argument checks shared by the domain factories; each message names the offending field. */
final class Require {
  private Require() {}

  static <T> T nonNull(T value, String field) {
    return Objects.requireNonNull(value, field + " must not be null");
  }

  static String nonBlank(String value, String field) {
    nonNull(value, field);
    if (value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  static long nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be >= 0, was " + value);
    }
    return value;
  }

  static int positive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be > 0, was " + value);
    }
    return value;
  }

  /** An unmodifiable copy of {@code values}; rejects a null list and null elements. */
  static <T> List<T> copy(List<T> values, String field) {
    nonNull(values, field);
    for (T value : values) {
      nonNull(value, field + " element");
    }
    return List.copyOf(values);
  }

  /** Like {@link #copy} but also rejects an empty list. */
  static <T> List<T> nonEmptyCopy(List<T> values, String field) {
    List<T> copy = copy(values, field);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    return copy;
  }

  /** {@code value} must be a 64-character lowercase hex SHA-256 digest. */
  static String sha256Hex(String value, String field) {
    nonNull(value, field);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
    }
    return value;
  }
}
