package dev.draftwatch.config;

import java.nio.file.Path;
import java.util.List;

/**
 * A config file could not be turned into a {@link DraftwatchConfig}. Every problem found is
 * reported, each naming its field; the message lists them one per line.
 */
public final class ConfigException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Path file;
  private final transient List<ConfigError> errors;

  public ConfigException(Path file, List<ConfigError> errors) {
    super(message(file, errors));
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("a ConfigException needs at least one error");
    }
    this.file = file;
    this.errors = List.copyOf(errors);
  }

  private static String message(Path file, List<ConfigError> errors) {
    StringBuilder text = new StringBuilder();
    text.append(file).append(": ").append(errors.size());
    text.append(errors.size() == 1 ? " error" : " errors");
    for (ConfigError error : errors) {
      text.append("\n  ").append(error);
    }
    return text.toString();
  }

  public Path file() {
    return file;
  }

  /** At least one, in the order found (document order). */
  public List<ConfigError> errors() {
    return errors;
  }
}
