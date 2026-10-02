package dev.draftwatch.app;

import dev.draftwatch.exec.slurm.CommandRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;

/** Runs the real wired CLI and captures what it prints. */
final class CommandTestSupport {
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final Cli cli;

  CommandTestSupport() {
    cli =
        new Bootstrap(
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8))
            .cli();
  }

  /** With {@code sleeper} between watch passes, and the real environment and clock. */
  CommandTestSupport(Sleeper sleeper) {
    cli =
        new Bootstrap(
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                System.getenv(),
                Clock.systemUTC(),
                sleeper)
            .cli();
  }

  /** With {@code slurm} answering every Slurm command, such as a {@code FakeSlurm}. */
  CommandTestSupport(CommandRunner slurm) {
    cli =
        new Bootstrap(
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                System.getenv(),
                Clock.systemUTC(),
                Sleeper.system(),
                slurm)
            .cli();
  }

  int run(String... args) {
    outBytes.reset();
    errBytes.reset();
    return cli.run(Arrays.asList(args));
  }

  String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }
}
