package dev.draftwatch.app;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** What every command receives: the config file location and the two output streams. */
public final class CommandContext {
  private final Path configFile;
  private final PrintStream out;
  private final PrintStream err;

  public CommandContext(Path configFile, PrintStream out, PrintStream err) {
    this.configFile = Objects.requireNonNull(configFile, "configFile");
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
  }

  /** From {@code --config}, or {@code ./draftwatch.yaml}; possibly relative to the CWD. */
  public Path configFile() {
    return configFile;
  }

  public PrintStream out() {
    return out;
  }

  public PrintStream err() {
    return err;
  }

  /** Prints {@code draftwatch: <message>} to stderr and returns {@link Cli#EXIT_FAILURE}. */
  public int fail(String message) {
    err.println("draftwatch: " + message);
    return Cli.EXIT_FAILURE;
  }

  /**
   * Returns {@link Cli#EXIT_USAGE} after reporting the first argument if {@code args} is not
   * empty; returns {@link Cli#EXIT_OK} otherwise. For commands that take no arguments.
   */
  public int requireNoArguments(String command, List<String> args) {
    if (args.isEmpty()) {
      return Cli.EXIT_OK;
    }
    err.println(
        "draftwatch " + command + ": unexpected argument '" + args.get(0) + "'; see --help");
    return Cli.EXIT_USAGE;
  }
}
