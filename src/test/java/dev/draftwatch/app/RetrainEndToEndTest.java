package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * ROADMAP M7 through the real wiring (local executor, fake harness, synthetic fixtures): a
 * regression submits the training command once per draft, and after the retrained draft is
 * deployed its results are never compared with the old draft's (DECISIONS.md D77–D79).
 */
public class RetrainEndToEndTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /** "Training": writes a different draft into the retrain run directory. */
  private static final List<String> TRAIN =
      List.of(
          "/bin/sh",
          "-c",
          "mkdir -p \"$DRAFTWATCH_RETRAIN_DIR/draft\""
              + " && printf retrained > \"$DRAFTWATCH_RETRAIN_DIR/draft/model.safetensors\"");

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();

  private int run(Project p, String... args) {
    List<String> argv = new ArrayList<>(List.of(args));
    argv.addAll(List.of("--config", p.config().toString()));
    return cli.run(argv.toArray(new String[0]));
  }

  private static void waitFor(Path file) throws InterruptedException {
    for (int i = 0; i < 300 && Files.notExists(file); i++) {
      Thread.sleep(100);
    }
    assertTrue(file + " never appeared", Files.exists(file));
  }

  /** Every stored result, by job id. */
  private Map<String, JsonNode> results(Project p) throws IOException {
    Map<String, JsonNode> out = new HashMap<>();
    try (Stream<Path> files = Files.walk(p.state().resolve("results"))) {
      for (Path f : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
        JsonNode r = json.readTree(f.toFile());
        out.put(r.at("/provenance/job_id").textValue(), r);
      }
    }
    return out;
  }

  private List<JsonNode> detections(Project p) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    for (String line : Files.readAllLines(p.state().resolve("detections.log"))) {
      out.add(json.readTree(line));
    }
    return out;
  }

  @Test
  public void aRetrainedDraftIsNeverComparedWithTheOldOne() throws Exception {
    String actions =
        "[notify, {retrain_draft: {command: " + json.writeValueAsString(TRAIN) + "}}]";
    Project p =
        new Project(tmp.getRoot().toPath().toAbsolutePath()).onRegression(actions).write();
    Path c100 = p.checkpoint(100, (byte) 1);
    Path c200 = p.checkpoint(200, (byte) 2);
    Path c300 = p.checkpoint(300, (byte) 3);
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "submit", "run", c100.toString())); // baseline
    String originalDraft =
        results(p).values().iterator().next().at("/provenance/draft_fingerprint").textValue();

    p.fixture("synthetic_three_prompts_lower.json").write();
    run(p, "submit", "run", c200.toString()); // a regression of the original draft
    assertTrue(cli.out(), cli.out().contains("REGRESSION"));
    assertTrue(cli.out(), cli.out().contains("retrain_draft: submitted training job retrain-j"));
    Path retrainDirs = p.state().resolve("retrain");
    List<Path> runs;
    try (Stream<Path> list = Files.list(retrainDirs)) {
      runs =
          list.filter(d -> d.getFileName().toString().startsWith("retrain-"))
              .collect(Collectors.toList());
    }
    assertEquals(1, runs.size());
    Path newDraft = runs.get(0).resolve("draft");
    waitFor(runs.get(0).resolve("exit_code"));
    assertEquals("0", Files.readString(runs.get(0).resolve("exit_code")).trim());
    assertTrue(Files.isRegularFile(newDraft.resolve("model.safetensors")));

    run(p, "submit", "run", c300.toString()); // the same draft regresses again
    assertTrue(cli.out(), cli.out().contains("was already sent for retraining"));
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "status"));
    assertTrue(cli.out(), cli.out().contains("retrain requests: 1"));

    // The user deploys the retrained draft; acceptance is back to the original fixture.
    p.draftPath(newDraft.toString()).fixture("synthetic_three_prompts.json").write();
    for (int i = 0; i < 200; i++) {
      assertEquals(cli.err(), Cli.EXIT_OK, run(p, "watch", "--once"));
      if (cli.out().contains(", 0 still running")) {
        break;
      }
      Thread.sleep(50);
    }
    assertTrue(cli.out(), cli.out().contains(", 0 still running"));

    Map<String, JsonNode> results = results(p);
    Set<String> probeHashes = new HashSet<>();
    Set<String> draftFingerprints = new HashSet<>();
    for (JsonNode r : results.values()) {
      probeHashes.add(r.at("/provenance/probe_hash").textValue());
      draftFingerprints.add(r.at("/provenance/draft_fingerprint").textValue());
    }
    assertEquals("one probe hash per draft", 2, probeHashes.size());
    assertEquals(2, draftFingerprints.size());

    int newDraftComparisons = 0;
    for (JsonNode d : detections(p)) {
      if (!d.hasNonNull("baseline_job_id")) {
        continue;
      }
      JsonNode current = results.get(d.get("job_id").textValue());
      JsonNode baseline = results.get(d.get("baseline_job_id").textValue());
      assertEquals(
          d.toString(),
          current.at("/provenance/draft_fingerprint").textValue(),
          baseline.at("/provenance/draft_fingerprint").textValue());
      assertEquals(
          d.toString(),
          current.at("/provenance/probe_hash").textValue(),
          baseline.at("/provenance/probe_hash").textValue());
      if (!current.at("/provenance/draft_fingerprint").textValue().equals(originalDraft)) {
        newDraftComparisons++;
        assertEquals(
            "same fixture as the new draft's own baseline: no regression",
            "OK",
            d.get("kind").textValue());
      }
    }
    assertTrue(
        "new-draft results were compared with the new draft's baseline: " + newDraftComparisons,
        newDraftComparisons >= 2);
    try (Stream<Path> list = Files.list(retrainDirs.resolve("requests"))) {
      assertEquals("no second retrain", 1, list.count());
    }
    assertFalse(cli.err(), cli.err().contains("subscriber"));
  }

  @Test
  public void validateShowsTheTrainingCommand() {
    Project p =
        new Project(tmp.getRoot().toPath().toAbsolutePath())
            .onRegression("[notify, {retrain_draft: {command: [python, train_draft.py]}}]")
            .write();
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "validate"));
    assertTrue(
        cli.out(),
        cli.out().contains("notify, retrain_draft (command: python train_draft.py)"));
  }
}
