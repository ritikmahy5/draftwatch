package dev.draftwatch.app;

import dev.draftwatch.config.ConfigLoader;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the top-level command line and dispatches to a {@link CliCommand}.
 *
 * <p>The global option {@code --config <file>} (or {@code --config=<file>}) may appear anywhere;
 * it defaults to {@code ./draftwatch.yaml}. Exit codes:
 * {@link #EXIT_OK} on success, {@link #EXIT_FAILURE} when a command fails (for example an
 * invalid config), {@link #EXIT_USAGE} for a bad command line.
 */
public final class Cli {
  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILURE = 1;
  public static final int EXIT_USAGE = 2;

  private static final String PROGRAM = "draftwatch";
  private static final String CONFIG_OPTION = "--config";

  private static final List<CommandUsage> OPTIONS =
      List.of(
          CommandUsage.of(CONFIG_OPTION, "<file>", "config file (default: ./draftwatch.yaml)"),
          CommandUsage.of("-h, --help", "", "show this help and exit"));

  private final List<CommandUsage> usages;
  private final Map<String, CliCommand> commands;
  private final PrintStream out;
  private final PrintStream err;

  /**
   * Creates a CLI.
   *
   * @param usages every command listed in help, in display order
   * @param commands the implemented commands by name; each must be listed in {@code usages}
   * @param out stream for requested output
   * @param err stream for errors and unrequested usage text
   * @throws IllegalArgumentException if an implemented command is not listed
   */
  public Cli(
      List<CommandUsage> usages,
      Map<String, CliCommand> commands,
      PrintStream out,
      PrintStream err) {
    this.usages = List.copyOf(usages);
    this.commands = Map.copyOf(commands);
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    for (String name : this.commands.keySet()) {
      if (!isListed(name)) {
        throw new IllegalArgumentException("command '" + name + "' is missing from help");
      }
    }
  }

  /** Runs the command named by {@code args} and returns the process exit code. */
  public int run(List<String> rawArgs) {
    List<String> args = new ArrayList<>();
    String configValue = null;
    for (int i = 0; i < rawArgs.size(); i++) {
      String arg = rawArgs.get(i);
      String value;
      if (arg.equals(CONFIG_OPTION)) {
        if (i + 1 >= rawArgs.size()) {
          return usageError(CONFIG_OPTION + " needs a file path");
        }
        value = rawArgs.get(++i);
      } else if (arg.startsWith(CONFIG_OPTION + "=")) {
        value = arg.substring(CONFIG_OPTION.length() + 1);
      } else {
        args.add(arg);
        continue;
      }
      if (configValue != null) {
        return usageError(CONFIG_OPTION + " given more than once");
      }
      if (value.isEmpty()) {
        return usageError(CONFIG_OPTION + " needs a file path");
      }
      configValue = value;
    }
    Path configFile;
    try {
      configFile = Paths.get(configValue == null ? ConfigLoader.DEFAULT_FILE_NAME : configValue);
    } catch (InvalidPathException e) {
      return usageError(CONFIG_OPTION + ": invalid path: " + e.getMessage());
    }
    return dispatch(args, configFile);
  }

  private int dispatch(List<String> args, Path configFile) {
    if (args.isEmpty()) {
      err.print(usage());
      return EXIT_USAGE;
    }
    String name = args.get(0);
    if (name.equals("--help") || name.equals("-h") || name.equals("help")) {
      out.print(usage());
      return EXIT_OK;
    }
    CliCommand command = commands.get(name);
    if (command != null) {
      CommandContext context = new CommandContext(configFile, out, err);
      return command.run(context, List.copyOf(args.subList(1, args.size())));
    }
    if (isListed(name)) {
      err.println(
          PROGRAM + ": command '" + name + "' is not implemented yet");
      return EXIT_USAGE;
    }
    return usageError("unknown command '" + name + "'; run '" + PROGRAM + " --help'");
  }

  private int usageError(String message) {
    err.println(PROGRAM + ": " + message);
    return EXIT_USAGE;
  }

  private boolean isListed(String name) {
    for (CommandUsage usage : usages) {
      if (usage.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** The full help text, ending with a newline. */
  public String usage() {
    int width = 0;
    for (CommandUsage usage : usages) {
      width = Math.max(width, usage.synopsis().length());
    }
    for (CommandUsage option : OPTIONS) {
      width = Math.max(width, option.synopsis().length());
    }
    StringBuilder text = new StringBuilder();
    text.append(PROGRAM).append(" - continuous integration for speculative decoding\n\n");
    text.append("Usage: ").append(PROGRAM).append(" [--config <file>] <command> [arguments]\n\n");
    text.append("Commands:\n");
    for (CommandUsage usage : usages) {
      line(text, usage.synopsis(), usage.summary(), width);
    }
    text.append("\nOptions:\n");
    for (CommandUsage option : OPTIONS) {
      line(text, option.synopsis(), option.summary(), width);
    }
    return text.toString();
  }

  private static void line(StringBuilder text, String left, String right, int width) {
    text.append("  ").append(left);
    for (int i = left.length(); i < width; i++) {
      text.append(' ');
    }
    text.append("  ").append(right).append('\n');
  }
}
