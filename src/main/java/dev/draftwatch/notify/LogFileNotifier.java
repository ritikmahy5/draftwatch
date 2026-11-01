package dev.draftwatch.notify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Appends one line per alert to {@code <state>/alerts.log}. The file is append-only; each line is
 * written with a single {@code O_APPEND} write, so concurrent appends do not interleave.
 */
public final class LogFileNotifier implements Notifier {
  public static final String FILE = "alerts.log";

  private final Path file;

  public LogFileNotifier(Path file) {
    this.file = Objects.requireNonNull(file, "file");
  }

  @Override
  public String name() {
    return "alerts.log";
  }

  @Override
  public void notify(String alertLine) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.write(
        file,
        (alertLine + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE);
  }
}
