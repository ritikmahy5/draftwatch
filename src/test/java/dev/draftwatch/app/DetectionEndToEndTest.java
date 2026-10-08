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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Detection through the real CLI: submit runs the measurement, the event bus delivers it to
 * detection, and outcomes reach detections.log, the console, and alerts.log. All numbers come
 * from synthetic fixtures; {@code synthetic_three_prompts_lower.json} accepts fewer tokens on
 * every prompt than {@code synthetic_three_prompts.json}.
 */
public class DetectionEndToEndTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String LOWER = "synthetic_three_prompts_lower.json";
  private static final String DETECTORS =
      "[{paired_bootstrap: {metric: alpha}}, {absolute_drop: {metric: alpha, max_drop: 0.1}}]";

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();

  private Project project() {
    return new Project(tmp.getRoot().toPath().toAbsolutePath()).detectors(DETECTORS);
  }

  private int submit(Project p, Path checkpoint) {
    return cli.run("submit", "run", checkpoint.toString(), "--config", p.config().toString());
  }

  private static String jobOf(String out) {
    Matcher m = Pattern.compile("job (j\\S+): probe chat").matcher(out);
    assertTrue(out, m.find());
    return m.group(1);
  }

  private List<JsonNode> detections(Project p) throws IOException {
    Path log = p.state().resolve("detections.log");
    List<JsonNode> records = new ArrayList<>();
    if (Files.exists(log)) {
      for (String line : Files.readAllLines(log)) {
        records.add(json.readTree(line));
      }
    }
    return records;
  }

  private List<JsonNode> detectionsOf(Project p, String job) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode r : detections(p)) {
      if (r.get("job_id").textValue().equals(job)) {
        out.add(r);
      }
    }
    return out;
  }

  private String alertsLog(Project p) throws IOException {
    Path log = p.state().resolve("alerts.log");
    return Files.exists(log) ? Files.readString(log) : "";
  }

  @Test
  public void firstCheckpointBecomesTheBaselineAndALaterDropIsARegression() throws IOException {
    Project p = project().write();
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, p.checkpoint(100, (byte) 1)));
    String first = jobOf(cli.out());
    assertTrue(cli.out(), cli.out().contains("baseline for target run pinned to step 100"));
    JsonNode baselines = json.readTree(p.state().resolve("baselines.json").toFile());
    assertEquals("auto", baselines.get("run").get("source").textValue());
    for (JsonNode r : detectionsOf(p, first)) {
      assertEquals(r.toString(), "INSUFFICIENT_DATA", r.get("kind").textValue());
    }
    assertEquals("", alertsLog(p));

    p.fixture(LOWER).write();
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, p.checkpoint(200, (byte) 2)));
    String second = jobOf(cli.out());
    List<JsonNode> records = detectionsOf(p, second);
    assertEquals(2, records.size());
    for (JsonNode r : records) {
      assertEquals(r.toString(), "REGRESSION", r.get("kind").textValue());
      assertEquals(first, r.get("baseline_job_id").textValue());
      assertTrue(Files.isRegularFile(Path.of(r.get("result_file").textValue())));
    }
    // fixtures: alpha 12/26 at the baseline, 7/26 now.
    assertEquals(7.0 / 26.0 - 12.0 / 26.0, records.get(1).get("observed").doubleValue(), 0.0);
    assertTrue(cli.out(), cli.out().contains("detection REGRESSION (job " + second));
    assertTrue(cli.err(), cli.err().contains("ALERT "));
    String alerts = alertsLog(p);
    assertEquals(alerts, 2, alerts.split("\n").length);
    String expectedStart = "REGRESSION target run, step 200, probe chat, job " + second;
    assertTrue(alerts, alerts.contains(expectedStart));
    String resultFile = records.get(0).get("result_file").textValue();
    assertTrue(alerts, alerts.contains("; result " + resultFile));
  }

  // --- an incomparable pair is ERROR, logged, alerted, not thrown -------------------------------

  @Test
  public void incomparablePairIsLoggedAndAlertedAndSubmitCarriesOn() throws IOException {
    Project p = project().write();
    submit(p, p.checkpoint(100, (byte) 1));
    p.fakeEnv("DRAFTWATCH_FAKE_HARNESS_VERSION", "fake-0.2.0").write();
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, p.checkpoint(200, (byte) 2)));
    String job = jobOf(cli.out());
    List<JsonNode> records = detectionsOf(p, job);
    assertEquals(2, records.size());
    for (JsonNode r : records) {
      assertEquals("ERROR", r.get("kind").textValue());
      assertEquals("incomparable: harness_version", r.get("explanation").textValue());
    }
    assertTrue(cli.out(), cli.out().contains("detection ERROR (job " + job));
    assertTrue(cli.err(), cli.err().contains("ERROR target run, step 200"));
    assertTrue(alertsLog(p), alertsLog(p).contains("incomparable: harness_version"));
    assertTrue(cli.out(), cli.out().contains("SUCCEEDED"));
  }

  // --- deferred detection when the baseline has no measurement yet ------------------------------

  @Test
  public void detectionIsDeferredUntilTheBaselineIsMeasured() throws IOException {
    Project p = project().write();
    Path baselineCkpt = p.checkpoint(100, (byte) 1);
    String config = p.config().toString();
    int exit = cli.run("baseline", "run", baselineCkpt.toString(), "--config", config);
    assertEquals(cli.err(), Cli.EXIT_OK, exit);
    assertTrue(cli.out(), cli.out().contains("set") && cli.out().contains("manually"));
    assertTrue(cli.out(), cli.out().contains("probe chat: no result for the baseline yet"));

    p.fixture(LOWER).write();
    submit(p, p.checkpoint(200, (byte) 2));
    String later = jobOf(cli.out());
    List<JsonNode> deferred = detectionsOf(p, later);
    assertEquals(1, deferred.size());
    assertEquals("DEFERRED", deferred.get(0).get("kind").textValue());
    assertEquals("", alertsLog(p));

    p.fixture("synthetic_three_prompts.json").write();
    submit(p, baselineCkpt);
    List<JsonNode> resolved = detectionsOf(p, later);
    assertEquals(3, resolved.size());
    assertEquals("REGRESSION", resolved.get(1).get("kind").textValue());
    assertEquals("REGRESSION", resolved.get(2).get("kind").textValue());
    assertTrue(alertsLog(p), alertsLog(p).contains("job " + later));

    cli.run("baseline", "run", "--config", config);
    assertTrue(cli.out(), cli.out().contains("probe chat: measured by job "));
  }

  @Test
  public void baselineCommandWithoutABaselineSaysWhatWillHappen() {
    Project p = project().write();
    assertEquals(Cli.EXIT_OK, cli.run("baseline", "run", "--config", p.config().toString()));
    assertTrue(cli.out(), cli.out().contains("no baseline yet; the first measured checkpoint"));
    assertEquals(
        Cli.EXIT_FAILURE, cli.run("baseline", "nope", "--config", p.config().toString()));
  }

  // --- a failing subscriber does not stop the others --------------------------------------------

  @Test
  public void unwritableAlertsLogDoesNotStopConsoleAlertsOrTheDetectionLog() throws IOException {
    Project p = project().write();
    submit(p, p.checkpoint(100, (byte) 1));
    Files.createDirectories(p.state().resolve("alerts.log")); // a directory: appends fail
    p.fixture(LOWER).write();
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, p.checkpoint(200, (byte) 2)));
    String job = jobOf(cli.out());
    assertTrue(cli.err(), cli.err().contains("ALERT "));
    assertTrue(cli.err(), cli.err().contains("subscriber on_regression run notify failed"));
    assertEquals(2, detectionsOf(p, job).size());
    assertFalse(Files.isRegularFile(p.state().resolve("alerts.log")));
  }
}
