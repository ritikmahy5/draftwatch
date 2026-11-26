package dev.draftwatch.scoring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.fingerprint.Sha256;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.FileBaselineRepository;
import dev.draftwatch.store.FileDetectionLog;
import dev.draftwatch.store.FileResultRepository;
import dev.draftwatch.store.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The score command end to end, on a synthetic state directory written by the real store
 * classes: one family, 40-prompt pools, N in {10, 20}, 20 subsets. Every count is invented.
 */
public class ScorerTest {
  private static final int POOL = 40;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final ObjectMapper json = new ObjectMapper();
  private final MetricCalculator calc = new MetricCalculator();
  private Path root;
  private Path state;

  /** Run 1 is stable; run 2 loses accepted tokens from step 900 (more on chat than on code). */
  private static int shift(Run run, ProbeKind kind, long step) {
    if (run == Run.RUN2 && step >= 900) {
      return kind == ProbeKind.CHAT || kind == ProbeKind.CHAT_LIVE ? 4 : 2;
    }
    return 0;
  }

  @Before
  public void setUp() throws IOException {
    root = tmp.getRoot().toPath().toRealPath();
    state = root.resolve("state");
    FileResultRepository results = new FileResultRepository(state, new JsonCodec(), json);
    FileBaselineRepository baselines = new FileBaselineRepository(state, json);
    ScoringFixtures.family(
        results, baselines, "qwen", POOL, ScorerTest::shift, Set.of("qwen-run2/qwen-chat/625"));
    EvaluationData data = EvaluationData.load(results, baselines);
    FileDetectionLog log = new FileDetectionLog(state, json);
    for (DetectionRecord r : ScoringFixtures.watchLog(data, DetectorConfigs.all(calc))) {
      log.append(r);
    }
  }

  private ScoreOptions options() {
    return ScoreOptions.defaults("0123456789abcdef0123456789abcdef01234567", root)
        .withSizes(List.of(10, 20))
        .withSubsetsPerSize(20)
        .withThreads(2);
  }

  private Path score(String name) {
    Path out = root.resolve(name);
    new Scorer(calc, json).score(state, out, options());
    return out;
  }

  private static List<String> files(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile)
          .map(p -> dir.relativize(p).toString())
          .sorted()
          .collect(Collectors.toList());
    }
  }

  @Test
  public void writesThreeFiles() throws IOException {
    assertEquals(List.of("alarm_rates.csv", "manifest.json", "score.json"), files(score("out")));
  }

  @Test
  public void scoreJsonHasTheSpecSections() throws IOException {
    JsonNode s = json.readTree(score("out").resolve("score.json").toFile());
    for (String section :
        List.of("tool", "inputs", "cross_check", "ground_truth", "scores", "drift", "cost",
            "operation")) {
      assertTrue(section, s.has(section));
    }
    // 4 probes x 24 compared checkpoints, less the missing one.
    assertEquals(95, s.path("cross_check").path("compared").asInt());
    assertEquals(
        "qwen-run2", s.path("inputs").path("missing").get(0).path("target").asText());
    JsonNode chat = s.path("ground_truth").path("qwen-chat");
    assertEquals(12 + 11, chat.path("checkpoints").size());
    JsonNode last = chat.path("checkpoints").get(22);
    assertEquals("run2", last.path("run").asText());
    assertEquals(1200, last.path("step").asLong());
    assertEquals("REGRESSED", last.path("label").asText());
    assertTrue(last.path("result_file").asText().startsWith("state/results/qwen-run2/"));
    JsonNode b0 = s.path("scores").path("qwen-chat").path("B0").path("20");
    assertEquals(23, b0.path("alarm_rates").size());
    assertTrue(b0.path("detection_rate").path("value").isNumber());
    assertEquals(2, b0.path("detection_rate").path("interval").size());
    assertTrue(b0.has("false_alarm_rate_excluding_real_drops"));
    assertTrue(b0.path("coverage").path("value").isNumber());
    assertEquals("SCORED", b0.path("delay").path("status").asText());
    assertEquals(900, b0.path("delay").path("onset_step").asLong());
    assertTrue(b0.path("run_false_alarm_rate").path("interval").isNull());
    JsonNode t = s.path("scores").path("qwen-chat").path("T").path("10");
    assertFalse(t.has("coverage"));
    assertFalse(t.has("false_alarm_rate_excluding_real_drops"));
    assertEquals(6, s.path("drift").path("qwen-code").size());
  }

  @Test
  public void manifestListsEveryInputWithSha256() throws IOException {
    JsonNode m = json.readTree(score("out").resolve("manifest.json").toFile());
    List<String> expected = new ArrayList<>();
    for (String f : files(state)) {
      expected.add("state/" + f);
    }
    List<String> listed = new ArrayList<>();
    for (JsonNode input : m.path("inputs")) {
      String path = input.path("path").asText();
      listed.add(path);
      assertEquals(
          path, Sha256.hex(Files.readAllBytes(root.resolve(path))), input.path("sha256").asText());
    }
    assertEquals(expected, listed);
    assertEquals(
        "0123456789abcdef0123456789abcdef01234567", m.path("tool_commit").asText());
    assertEquals(2, m.path("outputs").size());
  }

  @Test
  public void everyCsvRateTracesToScoreJson() throws IOException {
    Path out = score("out");
    JsonNode s = json.readTree(out.resolve("score.json").toFile());
    List<String> lines = Files.readAllLines(out.resolve("alarm_rates.csv"), StandardCharsets.UTF_8);
    assertEquals(
        "family,probe,config,n,run,step,delta_ref,lower,upper,label,flagged,subsets,rate",
        lines.get(0));
    // (23 chat + 24 code checkpoints) x 4 configurations x 2 sizes.
    assertEquals(1 + (23 + 24) * 4 * 2, lines.size());
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split(",", -1);
      JsonNode rates = s.path("scores").path(f[1]).path(f[2]).path(f[3]).path("alarm_rates");
      boolean found = false;
      for (JsonNode r : rates) {
        if (r.path("run").asText().equals(f[4]) && r.path("step").asText().equals(f[5])) {
          assertEquals(line, f[6], r.path("delta_ref").asText());
          assertEquals(line, f[9], r.path("label").asText());
          assertEquals(line, f[10], r.path("flagged").asText());
          assertEquals(line, f[11], r.path("subsets").asText());
          assertEquals(line, f[12], r.path("rate").asText());
          found = true;
        }
      }
      assertTrue(line, found);
    }
  }

  @Test
  public void outputIsByteIdenticalAcrossRuns() throws IOException {
    Path a = score("a");
    Path b = score("b");
    for (String f : files(a)) {
      assertArrayEquals(f, Files.readAllBytes(a.resolve(f)), Files.readAllBytes(b.resolve(f)));
    }
  }

  @Test
  public void refusesNonEmptyOut() throws IOException {
    Path out = root.resolve("out");
    Files.createDirectories(out);
    Files.write(out.resolve("old"), new byte[] {1});
    DataException e =
        assertThrows(
            DataException.class, () -> new Scorer(calc, json).score(state, out, options()));
    assertTrue(e.getMessage(), e.getMessage().contains("not empty"));
  }

  @Test
  public void crossCheckRunsFirst() throws IOException {
    Path log = state.resolve("detections.log");
    String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    // Change the first B0 interval bound of a compared checkpoint.
    int at = text.indexOf("\"interval_lower\":-");
    assertTrue(at > 0);
    String tampered =
        text.substring(0, at) + "\"interval_lower\":-9" + text.substring(at + 18);
    Files.write(log, tampered.getBytes(StandardCharsets.UTF_8));
    Path out = root.resolve("out");
    assertThrows(DataException.class, () -> new Scorer(calc, json).score(state, out, options()));
    assertFalse(Files.exists(out));
  }

  @Test
  public void noAbsolutePathsInOutput() throws IOException {
    Path out = score("out");
    for (String f : files(out)) {
      String text = new String(Files.readAllBytes(out.resolve(f)), StandardCharsets.UTF_8);
      assertFalse(f, text.contains(root.toString()));
      assertFalse(f, text.contains(tmp.getRoot().toString()));
    }
  }

  @Test
  public void inputOutsideTheRootFails() throws IOException {
    ScoreOptions elsewhere =
        ScoreOptions.defaults("c", state.resolve("results")).withSizes(List.of(10))
            .withSubsetsPerSize(2);
    DataException e =
        assertThrows(
            DataException.class,
            () -> new Scorer(calc, json).score(state, root.resolve("out"), elsewhere));
    assertTrue(e.getMessage(), e.getMessage().contains("outside"));
  }
}
