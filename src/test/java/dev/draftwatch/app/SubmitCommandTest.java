package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@code submit}, {@code status}, and {@code history} through the real wiring: Bootstrap,
 * LocalExecutor, the {@code /bin/sh} wrapper, and the fake harness. No GPU, Slurm, or network.
 */
public class SubmitCommandTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();

  /** The SPEC.md F4 provenance fields, as written in a result file. */
  private static final List<String> F4_FIELDS =
      List.of(
          "target",
          "checkpoint_path",
          "checkpoint_step",
          "checkpoint_fingerprint",
          "checkpoint_type",
          "base_model",
          "base_model_fingerprint",
          "checkpoint_final",
          "probe_id",
          "probe_hash",
          "draft_id",
          "draft_fingerprint",
          "harness_version",
          "backend",
          "dtype",
          "estimator",
          "seeds",
          "prompt_set_sha256",
          "executor",
          "job_id",
          "attempt",
          "start_time",
          "end_time",
          "raw_report_path");

  private Project project() {
    return new Project(tmp.getRoot().toPath().toAbsolutePath());
  }

  private int submit(Project p, Path checkpoint) {
    return cli.run("submit", "run", checkpoint.toString(), "--config", p.config().toString());
  }

  private List<Path> resultFiles(Project p) throws IOException {
    Path dir = p.state().resolve("results/run");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.collect(Collectors.toList());
    }
  }

  private static String resultPathIn(String out) {
    Matcher m = Pattern.compile("-> (\\S+\\.json)").matcher(out);
    assertTrue(out, m.find());
    return m.group(1);
  }

  // --- ROADMAP M2 "done when": every SPEC F4 provenance field is populated ---------------

  @Test
  public void submitStoresAMeasurementWithEveryProvenanceFieldPopulated() throws IOException {
    Project p = project().write();
    Path ckpt = p.checkpoint(100, (byte) 1);
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, ckpt));
    List<Path> files = resultFiles(p);
    assertEquals(1, files.size());
    assertEquals(files.get(0).toString(), resultPathIn(cli.out()));
    JsonNode provenance = json.readTree(files.get(0).toFile()).get("provenance");
    assertEquals(F4_FIELDS, fieldNames(provenance));
    for (String field : F4_FIELDS) {
      if (!field.startsWith("base_model")) {
        assertFalse(field + " is null", provenance.get(field).isNull());
        JsonNode value = provenance.get(field);
        assertFalse(field + " is empty", value.asText().isEmpty() && !value.isContainerNode());
      }
    }
    assertTrue("base model is recorded only for adapters", provenance.get("base_model").isNull());
    assertTrue(provenance.get("base_model_fingerprint").isNull());
    assertEquals(ckpt.toString(), provenance.get("checkpoint_path").textValue());
    assertEquals(100, provenance.get("checkpoint_step").intValue());
    assertEquals("local", provenance.get("executor").textValue());
    Path raw = Path.of(provenance.get("raw_report_path").textValue());
    assertTrue(Files.isRegularFile(raw));
    assertTrue(raw.startsWith(p.state().resolve("raw")));
  }

  @Test
  public void adapterSubmissionRecordsTheBaseModel() throws IOException {
    Project p = project().adapter().write();
    assertEquals(cli.err(), Cli.EXIT_OK, submit(p, p.checkpoint(200, (byte) 2)));
    JsonNode provenance = json.readTree(resultFiles(p).get(0).toFile()).get("provenance");
    assertEquals(p.dir().resolve("base").toString(), provenance.get("base_model").textValue());
    assertEquals(
        new SampledBlockFingerprinter().fingerprint(p.dir().resolve("base")),
        provenance.get("base_model_fingerprint").textValue());
    assertEquals("adapter", provenance.get("checkpoint_type").textValue());
    JsonNode report = json.readTree(resultFiles(p).get(0).toFile()).get("report");
    assertEquals("merged", report.get("adapter_handling").textValue());
  }

  @Test
  public void historyAndStatusShowTheStoredResult() throws IOException {
    Project p = project().write();
    submit(p, p.checkpoint(300, (byte) 3));
    submit(p, p.checkpoint(100, (byte) 1));
    String config = p.config().toString();

    int exit = cli.run("history", "run", "--probe", "chat", "--config", config);
    assertEquals(cli.err(), Cli.EXIT_OK, exit);
    String history = cli.out();
    assertTrue(history, history.contains("2 result(s)"));
    int first = history.indexOf("step 100");
    int second = history.indexOf("step 300");
    assertTrue("step order: " + history, first > 0 && second > first);
    for (Path file : resultFiles(p)) {
      assertTrue(history, history.contains(file.toString()));
    }

    assertEquals(Cli.EXIT_OK, cli.run("status", "--config", config));
    assertTrue(cli.out(), cli.out().contains("lock: free"));
    assertTrue(cli.out(), cli.out().contains("active jobs: 0"));
    assertTrue(cli.out(), cli.out().contains("failed in the last 7 days: 0"));
  }

  @Test
  public void resubmittingMeasuresAgainWithANote() throws IOException {
    Project p = project().write();
    Path ckpt = p.checkpoint(100, (byte) 1);
    submit(p, ckpt);
    assertEquals(Cli.EXIT_OK, submit(p, ckpt));
    assertTrue(cli.out(), cli.out().contains("note: 1 earlier result(s)"));
    assertEquals("results are append-only, keyed by job", 2, resultFiles(p).size());
  }

  // --- failures ------------------------------------------------------------------------

  @Test
  public void harnessOutOfMemoryFailsOnceAndShowsInStatus() throws IOException {
    Project p = project().fakeEnv("DRAFTWATCH_FAKE_EXIT", "4").maxRetries(3).write();
    assertEquals(Cli.EXIT_FAILURE, submit(p, p.checkpoint(100, (byte) 1)));
    assertTrue(cli.out(), cli.out().contains("FAILED: probe chat, attempt 1, out_of_memory"));
    assertTrue(resultFiles(p).isEmpty());
    cli.run("status", "--config", p.config().toString());
    assertTrue(cli.out(), cli.out().contains("failed in the last 7 days: 1"));
    assertTrue(cli.out(), cli.out().contains("out_of_memory: harness exited with code 4"));
  }

  @Test
  public void unexpectedExitIsRetriedUpToMaxRetries() throws IOException {
    Project p = project().fakeEnv("DRAFTWATCH_FAKE_EXIT", "1").maxRetries(2).write();
    assertEquals(Cli.EXIT_FAILURE, submit(p, p.checkpoint(100, (byte) 1)));
    assertTrue(cli.out(), cli.out().contains("attempt 3, unexpected_exit"));
    try (Stream<Path> raws = Files.list(p.state().resolve("raw"))) {
      Path jobRaw = raws.findFirst().orElseThrow();
      for (int attempt = 1; attempt <= 3; attempt++) {
        assertEquals("1\n", Files.readString(jobRaw.resolve("attempt-" + attempt + "/exit_code")));
      }
      assertFalse(Files.exists(jobRaw.resolve("attempt-4")));
    }
  }

  @Test
  public void corruptReportFailsNamingTheRule() {
    Project p =
        project().fakeEnv("DRAFTWATCH_FAKE_CORRUPT", "position_totals").maxRetries(2).write();
    assertEquals(Cli.EXIT_FAILURE, submit(p, p.checkpoint(100, (byte) 1)));
    String expected = "attempt 1, invalid_report: report rule position_totals";
    assertTrue(cli.out(), cli.out().contains(expected));
  }

  @Test
  public void incompleteCheckpointIsRejected() throws IOException {
    Project p = project().write();
    Path ckpt = p.checkpoint(100, (byte) 1);
    Files.delete(ckpt.resolve("DONE"));
    assertEquals(Cli.EXIT_FAILURE, submit(p, ckpt));
    assertTrue(cli.err(), cli.err().contains("not complete: marker file DONE does not exist yet"));
    assertFalse(Files.exists(p.state().resolve("jobs")));
  }

  @Test
  public void liveLockHolderBlocksSubmit() throws IOException {
    Project p = project().write();
    Files.createDirectories(p.state());
    ProcessHandle me = ProcessHandle.current();
    Files.writeString(
        p.state().resolve("lock"),
        "{\"host\":\"" + InetAddress.getLocalHost().getHostName() + "\",\"pid\":" + me.pid()
            + ",\"pid_start\":null,\"slurm_job_id\":null,"
            + "\"acquired_at\":\"2026-01-01T00:00:00Z\",\"command\":\"watch\"}");
    assertEquals(Cli.EXIT_FAILURE, submit(p, p.checkpoint(100, (byte) 1)));
    assertTrue(cli.err(), cli.err().contains("which is still running"));
  }

  @Test
  public void unknownTargetAndBadArgumentsAreReported() {
    Project p = project().write();
    assertEquals(
        Cli.EXIT_FAILURE,
        cli.run("submit", "nope", "x", "--config", p.config().toString()));
    assertTrue(cli.err(), cli.err().contains("unknown target 'nope'; configured: run"));
    assertEquals(Cli.EXIT_USAGE, cli.run("submit", "run", "--config", p.config().toString()));
    assertEquals(Cli.EXIT_USAGE, cli.run("history", "run", "--config", p.config().toString()));
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      names.add(it.next());
    }
    return names;
  }
}
