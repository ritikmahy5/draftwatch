package dev.draftwatch.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Target;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DirectoryCheckpointSourceTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path runs;
  private Path missing;
  private DirectoryCheckpointSource source;

  @Before
  public void setUp() {
    runs = tmp.getRoot().toPath().resolve("runs");
    missing = tmp.getRoot().toPath().resolve("other-runs");
    Target target =
        Target.builder()
            .name("run")
            .checkpointDirs(List.of(runs, missing))
            .checkpointType(CheckpointType.FULL)
            .build();
    source =
        new DirectoryCheckpointSource(
            target,
            new CheckpointInspector(
                new SampledBlockFingerprinter(), new ObjectMapper(), Clock.systemUTC()),
            new MarkerCompletionPolicy("DONE"));
  }

  private Path checkpoint(String name, boolean done) throws IOException {
    Path dir = Files.createDirectories(runs.resolve(name));
    Files.write(dir.resolve("model.safetensors"), name.getBytes());
    if (done) {
      Files.writeString(dir.resolve("DONE"), "");
    }
    return dir;
  }

  @Test
  public void findsCompleteCheckpointsInStepOrderAndReportsTheRest() throws IOException {
    checkpoint("checkpoint-300", true);
    checkpoint("checkpoint-20", true);
    checkpoint("checkpoint-1000", false);
    checkpoint("not-a-checkpoint", true);
    Files.createDirectories(runs.resolve(".hidden"));
    Files.writeString(runs.resolve("notes.txt"), "a file, not a directory");

    Discovery d = source.poll();

    assertEquals(
        List.of(20L, 300L),
        d.checkpoints().stream().map(Checkpoint::step).collect(Collectors.toList()));
    List<String> skipped =
        d.skipped().stream()
            .map(s -> s.kind() + " " + s.path().getFileName())
            .collect(Collectors.toList());
    assertEquals(
        List.of(
            "INCOMPLETE checkpoint-1000",
            "REJECTED not-a-checkpoint",
            "MISSING_DIRECTORY other-runs"),
        skipped);
    assertTrue(d.skipped().get(1).reason(), d.skipped().get(1).reason().contains("no step"));
  }

  @Test
  public void checkpointDirectoryThatDoesNotExistYetIsNotAnError() {
    Discovery d = source.poll();
    assertEquals(List.of(), d.checkpoints());
    assertEquals(2, d.skipped().size());
    for (Discovery.Skipped s : d.skipped()) {
      assertEquals(Discovery.SkipKind.MISSING_DIRECTORY, s.kind());
    }
  }

  @Test
  public void halfWrittenCheckpointAppearsOnceComplete() throws IOException {
    Path dir = checkpoint("checkpoint-50", false);
    assertEquals(List.of(), source.poll().checkpoints());
    Files.writeString(dir.resolve("DONE"), "");
    assertEquals(1, source.poll().checkpoints().size());
  }
}
