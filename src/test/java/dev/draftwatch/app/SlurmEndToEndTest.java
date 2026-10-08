package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.exec.slurm.SlurmExecutor;
import dev.draftwatch.testing.FakeSlurm;
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
 * The CLI with {@code executor.type: slurm} against {@link FakeSlurm}, a simulated cluster that
 * runs batch scripts locally; the harness is the fake harness, so every number is synthetic.
 */
public class SlurmEndToEndTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final FakeSlurm slurm = new FakeSlurm();
  private final CommandTestSupport cli = new CommandTestSupport(slurm);
  private final ObjectMapper json = new ObjectMapper();

  private Project project() {
    return new Project(tmp.getRoot().toPath().toAbsolutePath()).executor("slurm");
  }

  private int run(Project p, String... args) {
    List<String> argv = new ArrayList<>(List.of(args));
    argv.add("--config");
    argv.add(p.config().toString());
    return cli.run(argv.toArray(new String[0]));
  }

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

  private static String state(JsonNode job) {
    return job.get("state").textValue();
  }

  private void writeLock(Project p, String host, String slurmJobId) throws IOException {
    Files.createDirectories(p.state());
    Files.writeString(
        p.state().resolve("lock"),
        "{\"host\":\"" + host + "\",\"pid\":1,\"pid_start\":null,\"slurm_job_id\":\""
            + slurmJobId + "\",\"acquired_at\":\"2026-01-01T00:00:00Z\",\"command\":\"watch\"}");
  }

  @Test
  public void submitReturnsOnceSbatchAcceptsAndWatchOnceCollectsTheResult() throws IOException {
    Project p = project().write();
    Path ckpt = p.checkpoint(100, (byte) 1);
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "submit", "run", ckpt.toString()));
    assertTrue(cli.out(), cli.out().contains("SUBMITTED as slurm:9001"));
    assertTrue(cli.out(), cli.out().contains("submitted to Slurm; 'draftwatch watch --once'"));
    assertEquals("SUBMITTED", state(jobs(p).get(0)));

    FakeSlurm.Job job = slurm.jobs().get(0);
    Path runDir = job.script().getParent();
    assertEquals(runDir.resolve(SlurmExecutor.SCRIPT_FILE), job.script());
    assertEquals(runDir.resolve("stdout.log").toString(), job.option("output"));
    assertEquals(p.config().getParent().toString(), job.option("chdir"));
    assertTrue(job.options().contains("--requeue"));

    assertEquals(Cli.EXIT_OK, run(p, "watch", "--once"));
    assertTrue("still queued", cli.out().contains("0 finished, 1 still running"));
    slurm.runQueued();
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "watch", "--once"));
    assertTrue(cli.out(), cli.out().contains("SUCCEEDED: probe chat, alpha_mean"));
    assertTrue(cli.out(), cli.out().contains("1 finished, 0 still running"));
    JsonNode stored = jobs(p).get(0);
    assertEquals("SUCCEEDED", state(stored));
    assertEquals("slurm", stored.get("spec").get("executor").textValue());
    cli.run("history", "run", "--probe", "chat", "--config", p.config().toString());
    assertTrue(cli.out(), cli.out().contains("1 result(s)"));
  }

  @Test
  public void failedJobReportsTheHarnessExitCodeReason() throws IOException {
    Project p = project().fakeEnv("DRAFTWATCH_FAKE_EXIT", "3").write();
    run(p, "submit", "run", p.checkpoint(100, (byte) 1).toString());
    slurm.runQueued();
    assertEquals(Cli.EXIT_OK, run(p, "watch", "--once"));
    assertTrue(cli.out(), cli.out().contains("FAILED: probe chat, attempt 1, model_load"));
  }

  @Test
  public void pollErrorsAreReportedAndChangeNothing() throws IOException {
    Project p = project().write();
    run(p, "submit", "run", p.checkpoint(100, (byte) 1).toString());
    slurm.controllerDown(true);
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "watch", "--once"));
    assertTrue(cli.out(), cli.out().contains("not polled: cannot ask squeue about job 9001"));
    assertTrue(cli.out(), cli.out().contains("1 still running"));
    assertEquals("SUBMITTED", state(jobs(p).get(0)));
    slurm.controllerDown(false);
    slurm.runQueued();
    assertEquals(Cli.EXIT_OK, run(p, "watch", "--once"));
    assertEquals("SUCCEEDED", state(jobs(p).get(0)));
  }

  // --- a lock whose holder job is gone from squeue is taken over ------

  @Test
  public void lockWhoseSlurmJobIsGoneIsTakenOver() throws IOException {
    Project p = project().write();
    writeLock(p, "compute-17", "8000"); // squeue: Invalid job id specified
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "watch", "--once"));
    assertTrue(cli.out(), cli.out().contains("watch pass at"));
    assertTrue(slurm.commands().contains(
        List.of("squeue", "--noheader", "--states=all", "--format=%i|%T|%S", "--jobs=8000")));
    assertTrue("released after the pass", Files.notExists(p.state().resolve("lock")));
  }

  @Test
  public void lockWhoseSlurmJobIsQueuedIsNotTakenOver() throws IOException {
    Project p = project().write();
    run(p, "submit", "run", p.checkpoint(100, (byte) 1).toString()); // job 9001, PENDING
    writeLock(p, "compute-17", "9001");
    assertEquals(Cli.EXIT_FAILURE, run(p, "watch", "--once"));
    assertTrue(cli.err(), cli.err().contains("which is still running (squeue lists Slurm job 9001)"));
  }

  @Test
  public void statusDoesNotPollSlurm() throws IOException {
    Project p = project().write();
    run(p, "submit", "run", p.checkpoint(100, (byte) 1).toString());
    int before = slurm.commands().size();
    assertEquals(Cli.EXIT_OK, run(p, "status"));
    assertEquals(before, slurm.commands().size());
  }
}
