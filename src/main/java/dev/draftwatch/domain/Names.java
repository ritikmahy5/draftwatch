package dev.draftwatch.domain;

import java.util.regex.Pattern;

/**
 * Rules for user-chosen identifiers (target names, probe ids, draft ids). Target names become
 * directory names under the state directory, so identifiers are restricted to characters that
 * are safe in a single path segment on every filesystem (DECISIONS.md D20).
 */
public final class Names {
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /** Human-readable form of the rule, for error messages. */
  public static final String RULE =
      "must start with a letter or digit and contain only letters, digits, '.', '_', '-'";

  private Names() {}

  public static boolean isValid(String name) {
    return name != null && VALID.matcher(name).matches();
  }

  /**
   * Returns {@code name} if it is valid.
   *
   * @throws IllegalArgumentException naming {@code what} if it is not
   */
  static String require(String name, String what) {
    if (!isValid(name)) {
      throw new IllegalArgumentException(what + " '" + name + "' " + RULE);
    }
    return name;
  }
}
