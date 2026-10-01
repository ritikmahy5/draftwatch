package dev.draftwatch.app;

import java.util.List;

/**
 * One {@code draftwatch} subcommand (Command pattern: the CLI looks commands up by name and runs
 * them, so adding a command never changes the dispatcher).
 */
public interface CliCommand {
  /**
   * Runs the command.
   *
   * @param context the config file location and output streams
   * @param args the arguments after the command name, with global options removed
   * @return the process exit code: {@link Cli#EXIT_OK}, {@link Cli#EXIT_FAILURE}, or
   *     {@link Cli#EXIT_USAGE}
   */
  int run(CommandContext context, List<String> args);
}
