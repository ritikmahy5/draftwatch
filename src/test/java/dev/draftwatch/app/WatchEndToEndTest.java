package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@code watch} through the real wiring (LocalExecutor, fake harness, synthetic fixtures). The
 * chain {@code not_already_measured, max_pending(4), always_final, every_n_steps(200)} accepts
 * steps 200 and 400, rejects 100, and accepts 300 only because it is final.
 */
public class WatchEndToEndTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String TRIGGERS =
      "[{not_already_measured: {}}, {max_pending: 4}, {always_final: {}}, {every_n_steps: 200}]";

  private final CommandTestSupport cli = new CommandTestSupport();
  private final ObjectMapper json = new ObjectMapper();

  private Project project() {
    return new Project(tmp.getRoot().toPath().toAbsolutePath()).triggers(TRIGGERS);
  }

  private int watchOnce(Project p) {
    return cli.run("watch", "--once", "--config", p.config().toString());
  }

  /** Runs watch passes until no job is running; fails if that takes too long. */
  private void drain(Project p) {
    for (int i = 0; i < 200; i++) {
      assertEquals(cli.err(), Cli.EXIT_OK, watchOnce(p));
      if (cli.out().contains(", 0 still running")) {
        return;
      }
      sleep(50);
    }
    fail("jobs still running after 200 passes: " + cli.out());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Every stored job: its checkpoint step and its (fingerprint, probe hash). */
  private List<JsonNode> jobs(Project p) throws IOException {
    Path dir = p.state().resolve("jobs");
    List<JsonNode> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.sorted().collect(Collectors.toList())) {
        out.add(json.readTree(f.toFile()));
      }
    }
    return out;
  }

  private static long step(JsonNode job) {
    return job.get("spec").get("checkpoint").get("step").longValue();
  }

  private static String key(JsonNode job) {
    return job.get("spec").get("checkpoint").get("fingerprint").textValue() + "/"
        + job.get("spec").get("probe").get("hash").textValue();
  }

  private Set<Long> steps(Project p) throws IOException {
    Set<Long> steps = new TreeSet<>();
    for (JsonNode job : jobs(p)) {
      steps.add(step(job));
    }
    return steps;
  }

  private void assertNoDuplicateJobs(Project p) throws IOException {
    Set<String> seen = new HashSet<>();
    for (JsonNode job : jobs(p)) {
      assertTrue("duplicate job for " + key(job), seen.add(key(job)));
    }
  }

  private static void markDone(Path checkpoint) throws IOException {
    Files.writeString(checkpoint.resolve("DONE"), "");
  }

  // --- ROADMAP M4 "done when": checkpoints appearing over time, restarts ---------------------

  @Test
  public void checkpointsAppearingOverTimeAreMeasuredExactlyAsTheRulesAllow()
      throws IOException {
    Project p = project().write();
    p.checkpoint(100, (byte) 1);
    p.checkpoint(200, (byte) 2);

    assertEquals(cli.err(), Cli.EXIT_OK, watchOnce(p));
    assertEquals(Set.of(200L), steps(p));
    String rejected = "not measured, by deciding rule: every_n_steps(200) 1";
    assertTrue(cli.out(), cli.out().contains(rejected));

    Path half = p.halfWritten(300, (byte) 3);
    Files.writeString(half.resolve("FINAL"), "");
    p.checkpoint(400, (byte) 4);
    watchOnce(p);
    assertEquals("the half-written final checkpoint waits", Set.of(200L, 400L), steps(p));
    assertTrue(cli.out(), cli.out().contains("waiting: " + half));

    markDone(half);
    watchOnce(p);
    assertTrue(cli.out(), cli.out().contains("(always_final: final checkpoint)"));
    assertEquals(Set.of(200L, 300L, 400L), steps(p));

    drain(p); // many passes, each a fresh command: the restarts must not resubmit anything
    assertEquals(3, jobs(p).size());
    assertNoDuplicateJobs(p);
    for (JsonNode job : jobs(p)) {
      assertEquals(job.toString(), "SUCCEEDED", job.get("state").textValue());
    }
    assertEquals(Cli.EXIT_OK, watchOnce(p));
    assertTrue(cli.out(), cli.out().contains(": 0 submitted, 0 finished, 0 still running"));
    cli.run("history", "run", "--probe", "chat", "--config", p.config().toString());
    assertTrue(cli.out(), cli.out().contains("3 result(s)"));
  }

  @Test
  public void baselineWithoutAResultIsMeasuredEvenIfTheRulesWouldRejectIt() throws IOException {
    Project p = project().write();
    Path baseline = p.checkpoint(100, (byte) 1); // every_n_steps(200) rejects step 100
    cli.run("baseline", "run", baseline.toString(), "--config", p.config().toString());
    watchOnce(p);
    assertTrue(cli.out(), cli.out().contains("step 100 probe chat (baseline measurement)"));
    drain(p);
    assertEquals(1, jobs(p).size());
  }

  // --- ROADMAP M4 "done when": two concurrent watch --once never both submit ---------------

  @Test
  public void concurrentWatchProcessesNeverBothSubmit() throws Exception {
    Project p = project().write();
    p.checkpoint(200, (byte) 2);
    p.checkpoint(400, (byte) 4);
    Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
    List<String> command =
        List.of(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "dev.draftwatch.app.Main",
            "watch",
            "--once",
            "--config",
            p.config().toString());
    Process a =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(p.dir().resolve("a.log").toFile())
            .start();
    Process b =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(p.dir().resolve("b.log").toFile())
            .start();
    int exitA = a.waitFor();
    int exitB = b.waitFor();
    String logs =
        Files.readString(p.dir().resolve("a.log")) + Files.readString(p.dir().resolve("b.log"));
    assertTrue(logs, exitA == 0 || exitB == 0);
    if (exitA != 0 || exitB != 0) {
      assertTrue(logs, logs.contains("which is still running"));
    }
    assertEquals(logs, 2, jobs(p).size());
    assertNoDuplicateJobs(p);
    drain(p);
    assertEquals(2, jobs(p).size());
  }

  @Test
  public void watchOnceWhileAnotherProcessHoldsTheLockSubmitsNothing() throws IOException {
    Project p = project().write();
    p.checkpoint(200, (byte) 2);
    Files.createDirectories(p.state());
    Files.writeString(
        p.state().resolve("lock"),
        "{\"host\":\"" + InetAddress.getLocalHost().getHostName() + "\",\"pid\":"
            + ProcessHandle.current().pid() + ",\"pid_start\":null,\"slurm_job_id\":null,"
            + "\"acquired_at\":\"2026-01-01T00:00:00Z\",\"command\":\"watch\"}");
    assertEquals(Cli.EXIT_FAILURE, watchOnce(p));
    assertTrue(cli.err(), cli.err().contains("which is still running"));
    assertEquals(0, jobs(p).size());
  }

  // --- the loop and its limits ---------------------------------------------------------------

  @Test
  public void loopRunsPassesUntilInterrupted() {
    Project p = project().write();
    int[] sleeps = {0};
    CommandTestSupport looping =
        new CommandTestSupport(
            duration -> {
              assertEquals(Duration.ofSeconds(5), duration);
              if (++sleeps[0] == 3) {
                throw new InterruptedException("test stop");
              }
            });
    int exit = looping.run("watch", "--interval", "5s", "--config", p.config().toString());
    assertEquals(looping.err(), Cli.EXIT_OK, exit);
    String out = looping.out();
    assertEquals(out, 3, out.split("watch pass at ", -1).length - 1);
    assertTrue(out, out.endsWith("watch stopped\n"));
    assertTrue("lock released", Files.notExists(p.state().resolve("lock")));
    Thread.interrupted(); // clear the flag the command restored
  }

  @Test
  public void loopIsRefusedForTheSlurmExecutor() {
    Project p = project().executor("slurm").write();
    assertEquals(Cli.EXIT_FAILURE, cli.run("watch", "--config", p.config().toString()));
    assertTrue(cli.err(), cli.err().contains("looping watch is refused with executor.type slurm"));
  }

  @Test
  public void badIntervalIsAUsageError() {
    Project p = project().write();
    assertEquals(
        Cli.EXIT_USAGE,
        cli.run("watch", "--interval", "soon", "--config", p.config().toString()));
    assertTrue(cli.err(), cli.err().contains("'soon' is not a duration"));
  }
}
