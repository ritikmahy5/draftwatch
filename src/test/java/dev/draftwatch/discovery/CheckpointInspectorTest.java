package dev.draftwatch.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Target;
import dev.draftwatch.fingerprint.AdapterFingerprint;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CheckpointInspectorTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final CompletionPolicy COMPLETE = (dir, now) -> Optional.empty();

  private final CheckpointInspector inspector =
      new CheckpointInspector(
          new SampledBlockFingerprinter(),
          new ObjectMapper(),
          Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC));
  private Path runs;

  @Before
  public void setUp() {
    runs = tmp.getRoot().toPath();
  }

  private Target target(CheckpointType type) {
    Target.Builder b =
        Target.builder().name("run").checkpointDirs(List.of(runs)).checkpointType(type);
    if (type == CheckpointType.ADAPTER) {
      b.baseModel(runs.resolve("base"));
    }
    return b.build();
  }

  private Path checkpoint(String name) throws IOException {
    Path dir = Files.createDirectories(runs.resolve(name));
    Files.write(dir.resolve("model.safetensors"), new byte[] {1, 2, 3});
    return dir;
  }

  @Test
  public void buildsACheckpointWithStepFingerprintAndType() throws IOException {
    Path dir = checkpoint("checkpoint-500");
    Checkpoint c = inspector.inspect(target(CheckpointType.FULL), dir, COMPLETE);
    assertEquals("run", c.targetName());
    assertEquals(dir.toAbsolutePath(), c.path());
    assertEquals(500, c.step());
    assertEquals(new SampledBlockFingerprinter().fingerprint(dir), c.fingerprint());
    assertEquals(CheckpointType.FULL, c.type());
    assertFalse(c.isFinal());
  }

  private Path baseModel(byte weight) throws IOException {
    Path base = Files.createDirectories(runs.resolve("base"));
    Files.write(base.resolve("model.safetensors"), new byte[] {weight, 9, 9});
    return base;
  }

  @Test
  public void finalMarkerIsDetected() throws IOException {
    Path dir = checkpoint("checkpoint-900");
    Files.writeString(dir.resolve("FINAL"), "");
    assertTrue(inspector.inspect(target(CheckpointType.FULL), dir, COMPLETE).isFinal());
  }

  @Test
  public void adapterFingerprintCoversAdapterAndBaseModel() throws IOException {
    Path dir = checkpoint("checkpoint-900");
    Path base = baseModel((byte) 1);
    Checkpoint c = inspector.inspect(target(CheckpointType.ADAPTER), dir, COMPLETE);
    SampledBlockFingerprinter f = new SampledBlockFingerprinter();
    assertEquals(Optional.of(base), c.baseModel());
    assertEquals(Optional.of(f.fingerprint(base)), c.baseModelFingerprint());
    assertEquals(
        AdapterFingerprint.combine(f.fingerprint(dir), f.fingerprint(base)), c.fingerprint());
  }

  @Test
  public void sameAdapterOnAnotherBaseModelIsAnotherCheckpoint() throws IOException {
    Path dir = checkpoint("checkpoint-900");
    baseModel((byte) 1);
    String before = inspector.inspect(target(CheckpointType.ADAPTER), dir, COMPLETE).fingerprint();
    baseModel((byte) 2);
    String after = inspector.inspect(target(CheckpointType.ADAPTER), dir, COMPLETE).fingerprint();
    assertTrue(!before.equals(after));
  }

  @Test
  public void adapterWithoutBaseWeightsIsRejected() throws IOException {
    Path dir = checkpoint("checkpoint-900");
    Files.createDirectories(runs.resolve("base"));
    try {
      inspector.inspect(target(CheckpointType.ADAPTER), dir, COMPLETE);
      fail("expected CheckpointRejectedException");
    } catch (CheckpointRejectedException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("cannot fingerprint base model"));
    }
  }

  @Test
  public void incompleteCheckpointIsRejected() throws IOException {
    Path dir = checkpoint("checkpoint-1");
    assertRejected(
        dir, new MarkerCompletionPolicy("DONE"), "not complete: marker file DONE does not exist");
  }

  @Test
  public void checkpointWithoutStepIsRejected() throws IOException {
    assertRejected(checkpoint("latest"), COMPLETE, "no step: ");
  }

  @Test
  public void checkpointWithoutWeightsIsRejected() throws IOException {
    Path dir = Files.createDirectories(runs.resolve("checkpoint-7"));
    Files.writeString(dir.resolve("config.json"), "{}");
    assertRejected(dir, COMPLETE, "cannot fingerprint: ");
  }

  @Test
  public void missingDirectoryIsRejected() {
    assertRejected(runs.resolve("checkpoint-404"), COMPLETE, "not a directory");
  }

  private void assertRejected(Path dir, CompletionPolicy policy, String fragment) {
    try {
      inspector.inspect(target(CheckpointType.FULL), dir, policy);
      fail("expected CheckpointRejectedException");
    } catch (CheckpointRejectedException e) {
      assertEquals(dir.toAbsolutePath().normalize(), e.dir());
      assertTrue(e.getMessage(), e.getMessage().contains(fragment));
    }
  }
}
