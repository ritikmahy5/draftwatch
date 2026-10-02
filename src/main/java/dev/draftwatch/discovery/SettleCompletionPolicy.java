package dev.draftwatch.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Complete when no file under the directory has changed for at least the settle time
 * (SPEC.md F1, {@code settle_seconds}).
 *
 * <p>Stateless (DECISIONS.md D35): instead of comparing sizes and modification times across two
 * polls, it requires the newest modification time of every file to be at least the settle time in
 * the past. Writing to a file updates its modification time, so a file still being written is
 * always recent; and copy tools that restore original times ({@code cp -p}, {@code rsync -t}) set
 * them only after the data is complete.
 */
public final class SettleCompletionPolicy implements CompletionPolicy {
  private final Duration settle;

  public SettleCompletionPolicy(Duration settle) {
    this.settle = Objects.requireNonNull(settle, "settle");
    if (settle.isNegative() || settle.isZero()) {
      throw new IllegalArgumentException("settle time must be positive, was " + settle);
    }
  }

  @Override
  public Optional<String> incompleteReason(Path dir, Instant now) {
    Optional<Instant> newest;
    try {
      newest = newestModification(dir);
    } catch (IOException | UncheckedIOException e) {
      return Optional.of("cannot read " + dir + ": " + e.getMessage());
    }
    if (newest.isEmpty()) {
      return Optional.of("it contains no files yet");
    }
    Duration quiet = Duration.between(newest.get(), now);
    if (quiet.compareTo(settle) >= 0) {
      return Optional.empty();
    }
    return Optional.of(
        "a file changed " + Math.max(0, quiet.getSeconds()) + "s ago; settle_seconds is "
            + settle.getSeconds());
  }

  private static Optional<Instant> newestModification(Path dir) throws IOException {
    Instant newest = null;
    try (Stream<Path> walk = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
      for (Iterator<Path> it = walk.filter(Files::isRegularFile).iterator(); it.hasNext(); ) {
        Instant modified = Files.getLastModifiedTime(it.next()).toInstant();
        if (newest == null || modified.isAfter(newest)) {
          newest = modified;
        }
      }
    }
    return Optional.ofNullable(newest);
  }
}
