package dev.draftwatch.discovery;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Target;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A target's checkpoints in its {@code checkpoint_dirs} (SPEC.md F1; DECISIONS.md D54): every
 * immediate subdirectory not starting with {@code .} is inspected with the target's completion
 * policy, step extraction, and fingerprinting. A missing checkpoint directory or an incomplete
 * checkpoint is reported, not an error; a complete directory that is not a valid checkpoint is
 * reported as rejected.
 */
public final class DirectoryCheckpointSource implements CheckpointSource {
  private final Target target;
  private final CheckpointInspector inspector;
  private final CompletionPolicy completion;

  public DirectoryCheckpointSource(
      Target target, CheckpointInspector inspector, CompletionPolicy completion) {
    this.target = Objects.requireNonNull(target, "target");
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.completion = Objects.requireNonNull(completion, "completion");
  }

  @Override
  public Discovery poll() {
    List<Checkpoint> checkpoints = new ArrayList<>();
    List<Discovery.Skipped> skipped = new ArrayList<>();
    for (Path root : target.checkpointDirs()) {
      if (!Files.isDirectory(root)) {
        skipped.add(
            new Discovery.Skipped(
                root, Discovery.SkipKind.MISSING_DIRECTORY, "does not exist yet"));
        continue;
      }
      for (Path dir : subdirectories(root, skipped)) {
        try {
          checkpoints.add(inspector.inspect(target, dir, completion));
        } catch (CheckpointRejectedException e) {
          skipped.add(
              new Discovery.Skipped(
                  e.dir(),
                  e.isIncomplete() ? Discovery.SkipKind.INCOMPLETE : Discovery.SkipKind.REJECTED,
                  e.getMessage()));
        }
      }
    }
    checkpoints.sort(
        Comparator.comparingLong(Checkpoint::step).thenComparing(c -> c.path().toString()));
    return new Discovery(checkpoints, skipped);
  }

  private static List<Path> subdirectories(Path root, List<Discovery.Skipped> skipped) {
    try (Stream<Path> list = Files.list(root)) {
      return list.filter(Files::isDirectory)
          .filter(p -> !p.getFileName().toString().startsWith("."))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      skipped.add(
          new Discovery.Skipped(
              root, Discovery.SkipKind.REJECTED, "cannot list directory: " + e.getMessage()));
      return List.of();
    }
  }
}
