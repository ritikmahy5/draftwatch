package dev.draftwatch.app;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs the real wired CLI and captures what it prints. */
final class CommandTestSupport {
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final Cli cli =
      new Bootstrap(
              new PrintStream(outBytes, true, StandardCharsets.UTF_8),
              new PrintStream(errBytes, true, StandardCharsets.UTF_8))
          .cli();

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
