package dev.draftwatch.app;

import java.util.Objects;

/** One line of CLI help: a command's name, its argument synopsis, and a one-line summary. */
public final class CommandUsage {
  private final String name;
  private final String arguments;
  private final String summary;

  private CommandUsage(String name, String arguments, String summary) {
    this.name = name;
    this.arguments = arguments;
    this.summary = summary;
  }

  /**
   * Creates a usage entry.
   *
   * @param name command name as typed on the command line; must be non-blank
   * @param arguments argument synopsis, possibly empty
   * @param summary one-line description; must be non-blank
   * @throws IllegalArgumentException if {@code name} or {@code summary} is blank
   */
  public static CommandUsage of(String name, String arguments, String summary) {
    requireNonBlank(name, "name");
    Objects.requireNonNull(arguments, "arguments");
    requireNonBlank(summary, "summary");
    return new CommandUsage(name, arguments, summary);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("CommandUsage." + field + " must be non-blank");
    }
  }

  public String name() {
    return name;
  }

  public String arguments() {
    return arguments;
  }

  public String summary() {
    return summary;
  }

  /** The command name followed by its arguments, as shown in help output. */
  public String synopsis() {
    return arguments.isEmpty() ? name : name + " " + arguments;
  }
}
