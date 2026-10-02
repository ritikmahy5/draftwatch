package dev.draftwatch.exec.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.SlurmConfig;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobSpec;
import dev.draftwatch.exec.LocalExecutor;
import dev.draftwatch.testing.ReplayCommandRunner;
import dev.draftwatch.testing.SettableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Submission, the generated batch script, and cancellation. */
public class SlurmExecutorTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant NOW = Instant.parse("2026-10-01T16:00:00Z");

  private final ReplayCommandRunner commands = new ReplayCommandRunner();
  private final SettableClock clock = new SettableClock(NOW);

  private SlurmExecutor executor(SlurmConfig config) {
    return new SlurmExecutor(config, new SlurmCli(commands), clock, w -> {});
  }

  private static SlurmConfig config(boolean requeue, List<String> extra) {
    return SlurmConfig.of(
        Optional.of("gpu"),
        Optional.of("gpu:1"),
        Optional.of("00:45:00"),
        requeue,
        extra,
        SlurmConfig.DEFAULT_SCHEDULE_SBATCH_ARGS);
  }

  private JobSpec spec(String runDirName, List<String> command) throws IOException {
    Path work = tmp.newFolder("work dir").toPath();
    return JobSpec.builder()
        .jobId("j20261001T160000Z-0000002a")
        .attempt(2)
        .command(command)
        .workingDir(work)
        .runDir(tmp.getRoot().toPath().resolve(runDirName).resolve("attempt-2"))
        .build();
  }

  private static CommandResult sbatchPrints(List<String> argv, int exit, String out, String err) {
    return CommandResult.of(argv, exit, out, err);
  }

  @Test
  public void submitPassesEveryOptionOnTheCommandLineAndRecordsIt() throws IOException {
    JobSpec spec = spec("raw", List.of("python", "measure.py", "--out", "report.json"));
    SlurmExecutor executor = executor(config(true, List.of("--mem=32G", "--account=lab")));
    Path runDir = spec.runDir();
    Path script = runDir.resolve(SlurmExecutor.SCRIPT_FILE);
    List<String> expected =
        List.of(
            "sbatch",
            "--parsable",
            "--job-name=draftwatch-j20261001T160000Z-0000002a-a2",
            "--output=" + runDir.resolve("stdout.log"),
            "--error=" + runDir.resolve("stderr.log"),
            "--open-mode=append",
            "--chdir=" + spec.workingDir(),
            "--partition=gpu",
            "--gres=gpu:1",
            "--time=00:45:00",
            "--requeue",
            "--mem=32G",
            "--account=lab",
            script.toString(),
            "python",
            "measure.py",
            "--out",
            "report.json");
    commands.always(sbatchPrints(expected, 0, "4242\n", ""));
    JobHandle handle = executor.submit(spec);
    assertEquals("4242", handle.nativeId());
    assertEquals(SlurmExecutor.NAME, handle.executor());
    assertEquals(NOW, handle.submittedAt());
    assertEquals(runDir, handle.runDir());
    List<String> recorded = new ArrayList<>();
    new ObjectMapper()
        .readTree(runDir.resolve(SlurmExecutor.SBATCH_FILE).toFile())
        .forEach(n -> recorded.add(n.textValue()));
    assertEquals(expected, recorded);
    assertTrue(Files.readString(script).startsWith("#!/bin/sh\n"));
  }

  @Test
  public void noRequeueAndOmittedResourcesAreNotPassed() throws IOException {
    JobSpec spec = spec("raw", List.of("true"));
    SlurmConfig bare =
        SlurmConfig.of(
            Optional.empty(), Optional.empty(), Optional.empty(), false, List.of(), List.of());
    List<String> options = executor(bare).options(spec);
    assertTrue(options.contains("--no-requeue"));
    assertFalse(options.contains("--requeue"));
    for (String o : options) {
      assertFalse(o, o.startsWith("--partition") || o.startsWith("--gres") || o.startsWith("--time"));
    }
  }

  @Test
  public void aClusterNameIsKeptAndPassedToLaterCommands() throws IOException {
    JobSpec spec = spec("raw", List.of("true"));
    SlurmExecutor executor = executor(config(true, List.of()));
    List<String> argv =
        SlurmCli.sbatchArgv(
            executor.options(spec), spec.runDir().resolve(SlurmExecutor.SCRIPT_FILE),
            spec.command());
    commands.always(sbatchPrints(argv, 0, "4242;explorer\n", ""));
    JobHandle handle = executor.submit(spec);
    assertEquals("4242;explorer", handle.nativeId());
    SlurmJobId id = SlurmJobId.parse(handle.nativeId());
    assertEquals(
        List.of("squeue", "--noheader", "--states=all", "--format=%i|%T|%S", "--jobs=4242",
            "--clusters=explorer"),
        SlurmCli.squeueArgv(id));
    assertEquals(List.of("scancel", "--clusters=explorer", "4242"), SlurmCli.scancelArgv(id));
  }

  @Test
  public void refusedSubmissionNamesSbatchsMessage() throws IOException {
    JobSpec spec = spec("raw", List.of("true"));
    SlurmExecutor executor = executor(config(true, List.of()));
    List<String> argv =
        SlurmCli.sbatchArgv(
            executor.options(spec), spec.runDir().resolve(SlurmExecutor.SCRIPT_FILE),
            spec.command());
    commands.always(
        sbatchPrints(
            argv, 1, "", "sbatch: error: Batch job submission failed: Invalid partition name\n"));
    try {
      executor.submit(spec);
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().startsWith("sbatch refused the job: sbatch"));
      assertTrue(e.getMessage(), e.getMessage().contains("Invalid partition name"));
    }
  }

  @Test
  public void sbatchOutputThatIsNotAJobIdFailsLoudly() throws IOException {
    JobSpec spec = spec("raw", List.of("true"));
    SlurmExecutor executor = executor(config(true, List.of()));
    List<String> argv =
        SlurmCli.sbatchArgv(
            executor.options(spec), spec.runDir().resolve(SlurmExecutor.SCRIPT_FILE),
            spec.command());
    commands.always(sbatchPrints(argv, 0, "Submitted batch job 4242\n", ""));
    try {
      executor.submit(spec);
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("instead of a job id; the job may have"));
    }
  }

  @Test
  public void runDirectoryWithPercentIsRefusedBeforeSbatchRuns() throws IOException {
    JobSpec spec = spec("raw%j", List.of("true"));
    try {
      executor(config(true, List.of())).submit(spec);
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("contains '%'"));
    }
    assertTrue(commands.calls().isEmpty());
  }

  @Test
  public void batchScriptRunsItsArgumentsAndRecordsTheExitStatus() throws Exception {
    JobSpec spec =
        spec("it's raw", List.of("/bin/sh", "-c", "printf '%s' \"$1\" > arg.txt; exit 3", "x",
            "one arg with 'quotes'"));
    Path runDir = spec.runDir();
    Files.createDirectories(runDir);
    Path script = runDir.resolve(SlurmExecutor.SCRIPT_FILE);
    Files.writeString(script, SlurmExecutor.script(spec));
    Path exitFile = runDir.resolve(LocalExecutor.EXIT_FILE);
    Files.writeString(exitFile, "0\n"); // a stale one from a run before a requeue
    List<String> command = new ArrayList<>(List.of("/bin/sh", script.toString()));
    command.addAll(spec.command());
    Process p = new ProcessBuilder(command).directory(spec.workingDir().toFile()).start();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    assertEquals("the script exits with the harness's status", 3, p.exitValue());
    assertEquals("3\n", Files.readString(exitFile));
    assertEquals(
        "arguments arrive intact",
        "one arg with 'quotes'",
        Files.readString(spec.workingDir().resolve("arg.txt")));
    assertFalse(Files.exists(runDir.resolve(LocalExecutor.EXIT_FILE + ".tmp")));
  }

  @Test
  public void killedRunLeavesNoStaleExitCode() throws Exception {
    JobSpec spec = spec("raw", List.of("/bin/sh", "-c", "kill -9 $PPID"));
    Path runDir = spec.runDir();
    Files.createDirectories(runDir);
    Path script = runDir.resolve(SlurmExecutor.SCRIPT_FILE);
    Files.writeString(script, SlurmExecutor.script(spec));
    Files.writeString(runDir.resolve(LocalExecutor.EXIT_FILE), "0\n");
    List<String> command = new ArrayList<>(List.of("/bin/sh", script.toString()));
    command.addAll(spec.command());
    Process p = new ProcessBuilder(command).directory(spec.workingDir().toFile()).start();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    assertFalse(
        "a run killed before finishing must not leave the previous exit code",
        Files.exists(runDir.resolve(LocalExecutor.EXIT_FILE)));
  }

  @Test
  public void cancelOnlyCancelsAJobSqueueListsAlive() throws IOException {
    SlurmExecutor executor = executor(config(true, List.of()));
    JobHandle handle =
        JobHandle.of(SlurmExecutor.NAME, "4242", tmp.newFolder("r").toPath(), NOW,
            Optional.empty());
    SlurmJobId id = SlurmJobId.parse("4242");
    commands.always(CommandResult.of(SlurmCli.scancelArgv(id), 0, "", ""));
    commands.observe(
        List.of(CommandResult.of(SlurmCli.squeueArgv(id), 0, "4242|RUNNING|N/A\n", "")));
    executor.cancel(handle);
    assertEquals(1, commands.calls("scancel").size());
    commands.observe(
        List.of(CommandResult.of(SlurmCli.squeueArgv(id), 0, "4242|COMPLETED|N/A\n", "")));
    executor.cancel(handle);
    commands.observe(
        List.of(
            CommandResult.of(
                SlurmCli.squeueArgv(id), 1, "",
                "slurm_load_jobs error: Invalid job id specified\n")));
    executor.cancel(handle);
    assertEquals("finished and unknown jobs are left alone", 1, commands.calls("scancel").size());
  }
}
