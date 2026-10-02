package dev.draftwatch.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Complete when the marker file the training script writes last exists in the directory. */
public final class MarkerCompletionPolicy implements CompletionPolicy {
  private final String marker;

  public MarkerCompletionPolicy(String marker) {
    this.marker = Objects.requireNonNull(marker, "marker");
  }

  @Override
  public Optional<String> incompleteReason(Path dir, Instant now) {
    return Files.isRegularFile(dir.resolve(marker))
        ? Optional.empty()
        : Optional.of("marker file " + marker + " does not exist yet");
  }
}
