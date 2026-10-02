package dev.draftwatch.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CompletionPolicyTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private Path dir;

  @Before
  public void setUp() throws IOException {
    dir = tmp.newFolder("checkpoint-100").toPath();
  }

  private void file(String name, Instant modified) throws IOException {
    Path f = dir.resolve(name);
    Files.createDirectories(f.getParent());
    Files.writeString(f, name);
    Files.setLastModifiedTime(f, FileTime.from(modified));
  }

  @Test
  public void markerPolicyWaitsForTheMarker() throws IOException {
    MarkerCompletionPolicy policy = new MarkerCompletionPolicy("DONE");
    file("model.safetensors", NOW);
    assertEquals(
        Optional.of("marker file DONE does not exist yet"), policy.incompleteReason(dir, NOW));
    file("DONE", NOW);
    assertEquals(Optional.empty(), policy.incompleteReason(dir, NOW));
  }

  @Test
  public void settlePolicyNeedsEveryFileQuietForTheSettleTime() throws IOException {
    SettleCompletionPolicy policy = new SettleCompletionPolicy(Duration.ofSeconds(120));
    file("model-00001.safetensors", NOW.minusSeconds(600));
    file("sub/model-00002.safetensors", NOW.minusSeconds(30));
    Optional<String> reason = policy.incompleteReason(dir, NOW);
    assertEquals(Optional.of("a file changed 30s ago; settle_seconds is 120"), reason);
    Files.setLastModifiedTime(
        dir.resolve("sub/model-00002.safetensors"), FileTime.from(NOW.minusSeconds(120)));
    assertEquals(Optional.empty(), policy.incompleteReason(dir, NOW));
  }

  @Test
  public void settlePolicyRejectsAnEmptyDirectory() {
    SettleCompletionPolicy policy = new SettleCompletionPolicy(Duration.ofSeconds(1));
    assertEquals(Optional.of("it contains no files yet"), policy.incompleteReason(dir, NOW));
  }

  @Test
  public void settlePolicyReportsAnUnreadableDirectory() {
    SettleCompletionPolicy policy = new SettleCompletionPolicy(Duration.ofSeconds(1));
    Optional<String> reason = policy.incompleteReason(dir.resolve("missing"), NOW);
    assertTrue(reason.toString(), reason.get().startsWith("cannot read "));
  }

  @Test(expected = IllegalArgumentException.class)
  public void settleTimeMustBePositive() {
    new SettleCompletionPolicy(Duration.ZERO);
  }
}
