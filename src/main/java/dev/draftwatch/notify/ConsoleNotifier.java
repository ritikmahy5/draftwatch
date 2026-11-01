package dev.draftwatch.notify;

import java.io.PrintStream;
import java.util.Objects;

/** Prints {@code ALERT <line>} to a stream (standard error in the CLI). */
public final class ConsoleNotifier implements Notifier {
  private final PrintStream stream;

  public ConsoleNotifier(PrintStream stream) {
    this.stream = Objects.requireNonNull(stream, "stream");
  }

  @Override
  public String name() {
    return "console";
  }

  @Override
  public void notify(String alertLine) {
    stream.println("ALERT " + alertLine);
  }
}
