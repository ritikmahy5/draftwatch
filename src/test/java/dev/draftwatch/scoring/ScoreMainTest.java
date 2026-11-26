package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScoreMainTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(String... args) {
    return ScoreMain.run(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private int runWith(java.util.Map<String, String> build, String... args) {
    return ScoreMain.run(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        build);
  }

  private String err() {
    return new String(err.toByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  public void unknownSubcommandIsAUsageError() {
    assertEquals(2, run("nope"));
    assertTrue(err().contains("usage:"));
  }

  @Test
  public void exportWithoutClusterPrefixIsAUsageError() {
    assertEquals(2, run("export", "--state", "a", "--out", "b"));
    assertTrue(err(), err().contains("missing --cluster-prefix"));
  }

  @Test
  public void unknownFlagIsAUsageError() {
    assertEquals(2, run("export", "--state", "a", "--out", "b", "--cluster-prefix", "/x/y", "--z"));
    assertTrue(err(), err().contains("--z"));
  }

  @Test
  public void exportFailureExitsOneWithTheMessage() throws IOException {
    Path state = tmp.newFolder("state").toPath();
    assertEquals(
        1,
        run("export", "--state", state.toString(), "--out", tmp.getRoot() + "/out",
            "--cluster-prefix", "/scratch/someone"));
    assertTrue(err(), err().contains("results"));
    assertTrue(Files.notExists(tmp.getRoot().toPath().resolve("out")));
  }

  @Test
  public void scoreRefusesADirtyTree() {
    assertEquals(
        1,
        runWith(java.util.Map.of("draftwatch.commit", "abc", "draftwatch.dirty", "true"),
            "score", "--state", "a", "--out", "b"));
    assertTrue(err(), err().contains("uncommitted"));
  }

  @Test
  public void scoreNeedsTheCommit() {
    assertEquals(1, runWith(java.util.Map.of(), "score", "--state", "a", "--out", "b"));
    assertTrue(err(), err().contains("./gradlew score"));
  }

  @Test
  public void scoreRejectsABadSize() {
    assertEquals(
        2,
        runWith(java.util.Map.of("draftwatch.commit", "abc", "draftwatch.dirty", "false"),
            "score", "--state", "a", "--out", "b", "--sizes", "25,x"));
    assertTrue(err(), err().contains("--sizes"));
  }
}
