package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@code diff} on results from the fake harness and synthetic fixtures (DECISIONS.md D71): every
 * metric it prints equals the value in the result file it names.
 */
public class DiffEndToEndTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();

  private Project project() {
    return new Project(tmp.getRoot().toPath().toAbsolutePath()).write();
  }

  private void submit(Project p, Path ckpt) {
    cli.run("submit", "run", ckpt.toString(), "--config", p.config().toString());
  }

  private int diff(Project p, String... args) {
    List<String> argv = new ArrayList<>(List.of("diff"));
    argv.addAll(List.of(args));
    argv.addAll(List.of("--config", p.config().toString()));
    return cli.run(argv.toArray(new String[0]));
  }

  /** The two result files named in the output, A first. */
  private List<Path> files(String out) {
    return out.lines()
        .filter(l -> l.startsWith("   /") && l.endsWith(".json"))
        .map(l -> Path.of(l.trim()))
        .collect(Collectors.toList());
  }

  private static String pointer(String field) {
    if (!field.startsWith("seeds[")) {
      return "/report/aggregate/" + field;
    }
    String rest = field.replaceFirst("^seeds\\[(\\d+)\\]\\.", "/report/seeds/$1/");
    return rest.replaceFirst("\\[(\\d+)\\]$", "/$1");
  }

  @Test
  public void everyPrintedMetricEqualsTheValueInItsResultFile() throws IOException {
    Project p = project();
    Path a = p.checkpoint(100, (byte) 1);
    Path b = p.checkpoint(200, (byte) 2);
    submit(p, a);
    p.fixture("synthetic_three_prompts_lower.json").write();
    submit(p, b);
    assertEquals(cli.err(), Cli.EXIT_OK, diff(p, a.toString(), b.toString(), "--probe", "chat"));
    String out = cli.out();
    List<Path> files = files(out);
    assertEquals(out, 2, files.size());
    JsonNode fa = json.readTree(files.get(0).toFile());
    JsonNode fb = json.readTree(files.get(1).toFile());
    int checked = 0;
    boolean inMetrics = false;
    for (String line : out.lines().collect(Collectors.toList())) {
      String[] cols = line.trim().split("\\s+");
      if (cols.length == 3 && cols[0].equals("metric")) {
        inMetrics = true;
        continue;
      }
      if (line.isBlank()) {
        inMetrics = false;
      }
      if (inMetrics) {
        String ptr = pointer(cols[0]);
        assertEquals(line, text(fa.at(ptr)), cols[1]);
        assertEquals(line, text(fb.at(ptr)), cols[2]);
        checked++;
      }
    }
    assertTrue("metrics checked: " + checked, checked >= 4 + 8 + 3);
    assertTrue(out, out.contains("provenance fields that differ:"));
    assertTrue(out, out.contains("provenance.checkpoint_step"));
    assertTrue(out, out.contains("provenance.checkpoint_fingerprint"));
    assertTrue(out, out.endsWith("comparable: yes\n"));
  }

  private static String text(JsonNode n) {
    return n.isTextual() ? n.textValue() : n.toString();
  }

  @Test
  public void incomparableResultsSayWhichFieldDiffers() {
    Project p = project();
    Path a = p.checkpoint(100, (byte) 1);
    Path b = p.checkpoint(200, (byte) 2);
    submit(p, a);
    p.fakeEnv("DRAFTWATCH_FAKE_HARNESS_VERSION", "0.2.0").write();
    submit(p, b);
    assertEquals(Cli.EXIT_OK, diff(p, a.toString(), b.toString(), "--probe", "chat"));
    String out = cli.out();
    assertTrue(out, out.contains("report.harness_version"));
    assertTrue(out, out.contains("comparable: no, harness_version differs"));
  }

  @Test
  public void theLatestOfSeveralResultsIsComparedAndTheOthersNamed() throws IOException {
    Project p = project();
    Path a = p.checkpoint(100, (byte) 1);
    submit(p, a);
    submit(p, a);
    assertEquals(Cli.EXIT_OK, diff(p, a.toString(), a.toString(), "--probe", "chat"));
    assertTrue(cli.out(), cli.out().contains("(the latest of 2 results; also stored: j"));
    try (Stream<Path> files = Files.walk(p.state().resolve("results"))) {
      assertEquals(2, files.filter(Files::isRegularFile).count());
    }
  }

  @Test
  public void aDeletedCheckpointIsStillDiffedFromItsStoredResult() throws IOException {
    Project p = project();
    Path a = p.checkpoint(100, (byte) 1);
    Path b = p.checkpoint(200, (byte) 2);
    submit(p, a);
    submit(p, b);
    try (Stream<Path> walk = Files.walk(a)) {
      for (Path f : walk.sorted((x, y) -> y.compareTo(x)).collect(Collectors.toList())) {
        Files.delete(f);
      }
    }
    assertEquals(cli.err(), Cli.EXIT_OK, diff(p, a.toString(), b.toString(), "--probe", "chat"));
  }

  @Test
  public void missingResultsAndBadArgumentsAreReported() {
    Project p = project();
    Path a = p.checkpoint(100, (byte) 1);
    submit(p, a);
    Path elsewhere = p.dir().resolve("elsewhere/checkpoint-100");
    assertEquals(
        Cli.EXIT_FAILURE, diff(p, elsewhere.toString(), a.toString(), "--probe", "chat"));
    assertTrue(cli.err(), cli.err().contains("no stored result for checkpoint " + elsewhere));
    assertTrue(cli.err(), cli.err().contains("stored checkpoints with that name: " + a));
    assertEquals(Cli.EXIT_FAILURE, diff(p, a.toString(), a.toString(), "--probe", "other"));
    assertTrue(cli.err(), cli.err().contains("it has results for probes: chat"));
    assertEquals(Cli.EXIT_USAGE, diff(p, a.toString(), a.toString()));
    assertEquals(Cli.EXIT_USAGE, diff(p, a.toString(), "--probe", "chat"));
    assertEquals(Cli.EXIT_USAGE, diff(p, a.toString(), a.toString(), "--probe", "chat", "--x"));
  }
}
