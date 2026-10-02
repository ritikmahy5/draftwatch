package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.DraftwatchConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The generated schedule script, run with {@code /bin/sh}: a stub {@code sbatch} on the PATH
 * records its arguments and environment, and the launcher is a stub {@code watch} that crashes
 * (ROADMAP M5 "done when"; DECISIONS.md D63).
 */
public class ScheduleScriptTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String TOKEN = "0123456789abcdef0123456789abcdef";

  private Path log;
  private Path bin;
  private Schedule schedule;

  @Before
  public void setUp() throws IOException {
    Path root = tmp.newFolder("it's a dir").toPath().toAbsolutePath(); // quoting is exercised
    Project p = new Project(root).executor("slurm").write();
    DraftwatchConfig config = new ConfigLoader(new ConfigValidator()).load(p.config());
    log = root.resolve("calls.log");
    bin = Files.createDirectories(root.resolve("bin"));
    Path sbatch = bin.resolve("sbatch");
    Files.writeString(
        sbatch,
        "#!/bin/sh\n"
            + "{ echo sbatch; for a in \"$@\"; do echo \"  $a\"; done;"
            + " echo \"  SBATCH_GRES=${SBATCH_GRES-unset}\"; } >> \"$FAKE_LOG\"\n"
            + "if [ -n \"$FAKE_SBATCH_FAIL\" ]; then\n"
            + "  echo 'sbatch: error: Batch job submission failed' >&2; exit 1\n"
            + "fi\n"
            + "n=$(cat \"$FAKE_COUNTER\" 2>/dev/null || echo 9100); n=$((n+1))\n"
            + "echo \"$n\" > \"$FAKE_COUNTER\"; echo \"$n\"\n");
    Files.setPosixFilePermissions(sbatch, PosixFilePermissions.fromString("rwxr-xr-x"));
    List<String> crashingWatch =
        List.of("/bin/sh", "-c", "echo \"ran: $*\" >> \"$FAKE_LOG\"; exit 70", "draftwatch");
    schedule = Schedule.of(config, crashingWatch);
    Files.createDirectories(schedule.dir());
    Files.writeString(
        schedule.script(),
        schedule.script(TOKEN, Duration.ofMinutes(15), Instant.parse("2026-10-01T16:00:00Z")));
    Files.writeString(schedule.active(), TOKEN + "\n");
  }

  /** Runs the script as Slurm job {@code jobId} would, with SBATCH_GRES set in its environment. */
  private int runAsJob(String jobId, Map<String, String> extraEnv) throws Exception {
    ProcessBuilder b =
        new ProcessBuilder("/bin/sh", schedule.script().toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(schedule.log().toFile()));
    Map<String, String> env = b.environment();
    env.put("PATH", bin + ":" + env.get("PATH"));
    env.put("SLURM_JOB_ID", jobId);
    env.put("SBATCH_GRES", "gpu:1");
    env.put("FAKE_LOG", log.toString());
    env.put("FAKE_COUNTER", log.resolveSibling("counter").toString());
    env.putAll(extraEnv);
    Process p = b.start();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    return p.exitValue();
  }

  private List<String> calls() throws IOException {
    return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
  }

  @Test
  public void resubmitsBeforeRunningWatchSoACrashDoesNotStopTheSchedule() throws Exception {
    assertEquals("watch's crash is the job's exit status", 70, runAsJob("9100", Map.of()));
    List<String> calls = calls();
    assertEquals("sbatch", calls.get(0));
    assertTrue(calls.toString(), calls.get(calls.size() - 1).startsWith("ran: watch --once"));
    List<String> expected = new ArrayList<>();
    for (String option : schedule.resubmitOptions(Duration.ofMinutes(15))) {
      expected.add("  " + option);
    }
    expected.add("  --parsable");
    assertTrue(calls.toString(), calls.containsAll(expected));
    assertTrue(calls.contains("  --begin=now+900"));
    assertEquals("  " + schedule.script(), calls.get(calls.indexOf("  SBATCH_GRES=unset") - 1));
    assertTrue("GPU variables are unset before sbatch", calls.contains("  SBATCH_GRES=unset"));
    for (String line : calls) {
      assertFalse(line, line.startsWith("  --gres") || line.startsWith("  --gpus"));
    }
    assertTrue(calls.toString(), calls.get(calls.size() - 1).endsWith(
        "--config " + schedule.dir().getParent().getParent().resolve("draftwatch.yaml")));
    assertEquals("9101", Files.readString(schedule.jobIdFile()).trim());

    // The next job runs the same script: the crash above did not stop the chain.
    assertEquals(70, runAsJob("9101", Map.of()));
    assertEquals("9102", Files.readString(schedule.jobIdFile()).trim());
    assertEquals(2, calls().stream().filter("sbatch"::equals).count());
    assertTrue(Files.readString(schedule.log()).contains("=== Slurm job 9101 on "));
  }

  @Test
  public void aScriptWhoseTokenIsGoneStopsWithoutResubmittingOrRunning() throws Exception {
    Files.delete(schedule.active()); // as after 'draftwatch unschedule'
    assertEquals(0, runAsJob("9100", Map.of()));
    assertEquals(List.of(), calls());
    Files.writeString(schedule.active(), "a-newer-schedule\n");
    assertEquals(0, runAsJob("9100", Map.of()));
    assertEquals(List.of(), calls());
    assertTrue(
        Files.readString(schedule.log()).contains("does not hold this schedule's token"));
  }

  @Test
  public void aFailedResubmissionIsRecordedAndThePassStillRuns() throws Exception {
    assertEquals(70, runAsJob("9100", Map.of("FAKE_SBATCH_FAIL", "1")));
    assertTrue(Files.exists(schedule.stopped()));
    assertFalse(Files.exists(schedule.jobIdFile()));
    assertTrue(calls().get(calls().size() - 1).startsWith("ran: watch --once"));
    String output = Files.readString(schedule.log());
    assertTrue(output, output.contains("resubmission failed; the schedule has stopped"));
    assertTrue(output, output.contains("sbatch: error: Batch job submission failed"));
  }
}
