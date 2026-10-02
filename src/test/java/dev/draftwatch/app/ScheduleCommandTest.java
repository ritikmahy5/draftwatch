package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.SbatchOptions;
import dev.draftwatch.testing.FakeSlurm;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@code schedule}, {@code unschedule}, and the schedule in {@code status}, on FakeSlurm. */
public class ScheduleCommandTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final FakeSlurm slurm = new FakeSlurm();
  private final CommandTestSupport cli = new CommandTestSupport(slurm);

  private Project project(String executor) {
    return new Project(tmp.getRoot().toPath().toAbsolutePath()).executor(executor).write();
  }

  private int run(Project p, String... args) {
    List<String> argv = new ArrayList<>(List.of(args));
    argv.add("--config");
    argv.add(p.config().toString());
    return cli.run(argv.toArray(new String[0]));
  }

  private Schedule schedule(Project p) {
    return Schedule.of(new ConfigLoader(new ConfigValidator()).load(p.config()), List.of());
  }

  @Test
  public void scheduleSubmitsACpuOnlyJobThatRunsAtOnce() throws IOException {
    Project p = project("slurm");
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "schedule", "--interval", "20m"));
    Schedule s = schedule(p);
    assertTrue(cli.out(), cli.out().contains("scheduled: Slurm job 9001 runs 'watch --once'"));
    assertTrue(cli.out(), cli.out().contains("then every 1200 s"));
    FakeSlurm.Job job = slurm.jobs().get(0);
    assertEquals(s.script(), job.script());
    assertEquals(s.options(), job.options());
    assertEquals(
        List.of(
            "--job-name=" + s.jobName(),
            "--output=" + s.log(),
            "--error=" + s.log(),
            "--open-mode=append",
            "--chdir=" + p.config().getParent(),
            "--no-requeue",
            "--time=00:30:00"),
        job.options());
    for (String option : job.options()) {
      assertFalse(option, option.startsWith("--gres") || option.startsWith("--gpus"));
    }
    assertEquals(SbatchOptions.GPU_VARIABLES, job.unset());
    assertTrue(s.jobName().matches("draftwatch-watch-[0-9a-f]{8}"));
    assertTrue(s.token().orElseThrow().matches("[0-9a-f]{32}"));
    assertEquals("9001", s.jobId().orElseThrow());
    String script = Files.readString(s.script());
    assertTrue(script, script.contains("'--begin=now+1200'"));
    assertTrue(script, script.contains("'watch' '--once' '--config' '" + p.config() + "'"));
    assertFalse("the lock is released", Files.exists(p.state().resolve("lock")));
  }

  @Test
  public void scheduleIsRefusedForTheLocalExecutorAndForShortIntervals() {
    Project local = project("local");
    assertEquals(Cli.EXIT_FAILURE, run(local, "schedule"));
    assertTrue(cli.err(), cli.err().contains("schedule needs executor.type slurm"));
    Project p = project("slurm");
    assertEquals(Cli.EXIT_USAGE, run(p, "schedule", "--interval", "30s"));
    assertTrue(cli.err(), cli.err().contains("must be at least 60s, was 30s"));
    assertEquals(Cli.EXIT_USAGE, run(p, "schedule", "--every", "5m"));
    assertTrue(slurm.jobs().isEmpty());
  }

  @Test
  public void aSecondScheduleIsRefusedWhileTheFirstIsQueuedAndReplacesItOnceItEnded() {
    Project p = project("slurm");
    run(p, "schedule");
    assertEquals(Cli.EXIT_FAILURE, run(p, "schedule"));
    assertTrue(cli.err(), cli.err().contains("already scheduled: Slurm job 9001 is PENDING"));
    slurm.run(List.of("scancel", "9001"), Map.of(), Set.of()); // the chain ended some other way
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "schedule"));
    assertTrue(cli.out(), cli.out().contains("replacing the schedule whose last job, 9001"));
    assertEquals(2, slurm.jobs().size());
    assertTrue(cli.out(), cli.out().contains("scheduled: Slurm job 9002"));
  }

  @Test
  public void unscheduleRemovesTheTokenAndCancelsQueuedJobs() throws IOException {
    Project p = project("slurm");
    run(p, "schedule");
    assertEquals(cli.err(), Cli.EXIT_OK, run(p, "unschedule"));
    assertTrue(cli.out(), cli.out().contains("cancelled queued Slurm job 9001"));
    assertTrue(cli.out(), cli.out().endsWith("unscheduled\n"));
    assertEquals("CANCELLED", slurm.jobs().get(0).state());
    Schedule s = schedule(p);
    assertFalse(Files.exists(s.active()));
    assertFalse(Files.exists(s.jobIdFile()));
    assertEquals(Cli.EXIT_OK, run(p, "unschedule"));
    assertTrue(cli.out(), cli.out().startsWith("nothing is scheduled for "));
  }

  @Test
  public void statusReportsTheSchedule() throws IOException {
    Project p = project("slurm");
    run(p, "status");
    assertTrue(cli.out(), cli.out().contains("schedule: none\n"));
    run(p, "schedule", "--interval", "1h");
    run(p, "status");
    assertTrue(cli.out(), cli.out().contains("schedule: active, latest Slurm job 9001"));
    Files.writeString(schedule(p).stopped(), "2026-10-01T16:15:00Z\n");
    run(p, "status");
    assertTrue(cli.out(), cli.out().contains("schedule: STOPPED at 2026-10-01T16:15:00Z"));
  }
}
