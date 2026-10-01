package dev.draftwatch.app;

import java.util.Arrays;

/** Process entry point: wires the application and exits with the command's exit code. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int code = new Bootstrap(System.out, System.err).cli().run(Arrays.asList(args));
    System.out.flush();
    System.exit(code);
  }
}
