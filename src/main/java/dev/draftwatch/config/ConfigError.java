package dev.draftwatch.config;

import java.util.Objects;

/**
 * One problem in a config file, located by a field path such as
 * {@code targets[0].completion.settle_seconds} ({@code <root>} for the document itself).
 */
public final class ConfigError {
  private final String field;
  private final String message;

  private ConfigError(String field, String message) {
    this.field = field;
    this.message = message;
  }

  public static ConfigError of(String field, String message) {
    return new ConfigError(Objects.requireNonNull(field), Objects.requireNonNull(message));
  }

  public String field() {
    return field;
  }

  public String message() {
    return message;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConfigError)) {
      return false;
    }
    ConfigError that = (ConfigError) o;
    return field.equals(that.field) && message.equals(that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(field, message);
  }

  /** {@code <field>: <message>}. */
  @Override
  public String toString() {
    return field + ": " + message;
  }
}
