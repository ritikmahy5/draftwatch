package dev.draftwatch.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Target;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.fingerprint.Fingerprinter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a directory into a {@link Checkpoint} of a target: checks completion, extracts the step,
 * fingerprints the weights, and detects the final marker (SPEC.md F1). Manual {@code submit}
 * and {@code watch} share it, so both apply the same rules.
 */
public final class CheckpointInspector {
  private final Fingerprinter fingerprinter;
  private final ObjectMapper json;
  private final Clock clock;

  public CheckpointInspector(Fingerprinter fingerprinter, ObjectMapper json, Clock clock) {
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Inspects {@code dir} as a checkpoint of {@code target}.
   *
   * @throws CheckpointRejectedException naming the directory if it is not a directory, is not
   *     complete under {@code completion}, has no determinable step, or has no weight files
   */
  public Checkpoint inspect(Target target, Path dir, CompletionPolicy completion) {
    Path path = dir.toAbsolutePath().normalize();
    if (!Files.isDirectory(path)) {
      throw new CheckpointRejectedException(path, "not a directory");
    }
    Optional<String> incomplete = completion.incompleteReason(path, clock.instant());
    if (incomplete.isPresent()) {
      throw new CheckpointRejectedException(path, "not complete: " + incomplete.get());
    }
    long step;
    try {
      step = new StepExtractor(target.stepRegex(), json).extract(path);
    } catch (StepExtractionException e) {
      throw new CheckpointRejectedException(path, "no step: " + e.getMessage(), e);
    }
    String fingerprint;
    try {
      fingerprint = fingerprinter.fingerprint(path);
    } catch (FingerprintException e) {
      throw new CheckpointRejectedException(path, "cannot fingerprint: " + e.getMessage(), e);
    }
    Checkpoint.Builder checkpoint =
        Checkpoint.builder()
            .targetName(target.name())
            .path(path)
            .step(step)
            .fingerprint(fingerprint)
            .type(target.checkpointType())
            .isFinal(Files.exists(path.resolve(target.finalMarker())));
    target.baseModel().ifPresent(checkpoint::baseModel);
    return checkpoint.build();
  }
}
