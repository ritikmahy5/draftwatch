package dev.draftwatch.app;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parses the top-level command line and dispatches to a command.
 *
 * <p>Exit codes: {@link #EXIT_OK} on success, {@link #EXIT_USAGE} for a missing, unknown, or
 * not-yet-implemented command (DECISIONS.md D19).
 */
public final class Cli {
  public static final int EXIT_OK = 0;
  public static final int EXIT_USAGE = 2;

  private static final String PROGRAM = "draftwatch";

  private final List<CommandUsage> commands;
  private final PrintStream out;
  private final PrintStream err;

  /**
   * Creates a CLI.
   *
   * @param commands every command listed in help, in display order
   * @param out stream for requested output (help)
   * @param err stream for errors and unrequested usage text
   */
  public Cli(List<CommandUsage> commands, PrintStream out, PrintStream err) {
    this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
  }

  /** Runs the command named by {@code args} and returns the process exit code. */
  public int run(List<String> args) {
    if (args.isEmpty()) {
      err.print(usage());
      return EXIT_USAGE;
    }
    String first = args.get(0);
    if (first.equals("--help") || first.equals("-h") || first.equals("help")) {
      out.print(usage());
      return EXIT_OK;
    }
    if (isListed(first)) {
      err.println(
          PROGRAM + ": command '" + first + "' is not implemented yet (see docs/ROADMAP.md)");
      return EXIT_USAGE;
    }
    err.println(PROGRAM + ": unknown command '" + first + "'; run '" + PROGRAM + " --help'");
    return EXIT_USAGE;
  }

  private boolean isListed(String name) {
    for (CommandUsage command : commands) {
      if (command.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** The full help text, ending with a newline. */
  public String usage() {
    int width = 0;
    for (CommandUsage command : commands) {
      width = Math.max(width, command.synopsis().length());
    }
    StringBuilder text = new StringBuilder();
    text.append(PROGRAM).append(" - continuous integration for speculative decoding\n\n");
    text.append("Usage: ").append(PROGRAM).append(" <command> [arguments]\n\n");
    text.append("Commands:\n");
    for (CommandUsage command : commands) {
      text.append("  ").append(pad(command.synopsis(), width)).append("  ");
      text.append(command.summary()).append('\n');
    }
    text.append("\nOptions:\n");
    text.append("  -h, --help  show this help and exit\n");
    return text.toString();
  }

  private static String pad(String value, int width) {
    StringBuilder padded = new StringBuilder(value);
    while (padded.length() < width) {
      padded.append(' ');
    }
    return padded.toString();
  }
}
