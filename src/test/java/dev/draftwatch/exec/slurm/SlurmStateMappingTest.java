package dev.draftwatch.exec.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.draftwatch.config.SlurmConfig;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.exec.LocalExecutor;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.ReplayCommandRunner;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SettableClock;
import dev.draftwatch.testing.SlurmScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Every row of ARCHITECTURE.md's Slurm state mapping, and the rules stated below the table,
 * replayed from a fixture through {@link SlurmExecutor} and the real {@link JobPoller}. The
 * fixtures named {@code synthetic_} were written by hand from the documented formats; recorded
 * {@code real_} ones replace them once {@code scripts/record_slurm_fixtures.py} has run on the
 * cluster.
 */
public class SlurmStateMappingTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant SUBMITTED_AT = Instant.parse("2026-10-01T16:00:00Z");

  private final ReplayCommandRunner commands = new ReplayCommandRunner();
  private final SettableClock clock = new SettableClock(SUBMITTED_AT);
  private final List<String> warnings = new ArrayList<>();
  private ReportScenario report;
  private MeasurementSpec spec;

  @Before
  public void setUp() {
    Path dir = tmp.getRoot().toPath();
    report = ReportScenario.greedy(dir);
    spec =
        MeasurementSpec.of(
            report.checkpoint(),
            report.probe(),
            report.harnessCommand(Map.of()),
            dir,
            dir.resolve("raw/j1"),
            SlurmExecutor.NAME);
  }

  private SlurmExecutor executor(boolean requeueOnPreempt) {
    SlurmConfig config =
        SlurmConfig.of(
            Optional.of("gpu"),
            Optional.of("gpu:1"),
            Optional.of("00:45:00"),
            requeueOnPreempt,
            List.of(),
            SlurmConfig.DEFAULT_SCHEDULE_SBATCH_ARGS);
    return new SlurmExecutor(config, new SlurmCli(commands), clock, warnings::add);
  }

  private Path runDir() {
    return spec.runDir(1);
  }

  /** As {@link SlurmExecutor#submit} leaves it. */
  private void createRunDir() {
    try {
      Files.createDirectories(runDir());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** The harness's side of a finished attempt: its exit code, and a valid report on exit 0. */
  private void harnessExited(int code) throws IOException {
    Files.createDirectories(runDir());
    if (code == 0) {
      assertEquals(0, report.run(Map.of()));
      Files.copy(report.reportPath(), spec.reportPath(1));
    }
    Files.writeString(runDir().resolve(LocalExecutor.EXIT_FILE), code + "\n");
  }

  /**
   * Replays {@code name} and returns the job after each observation, each poll at the
   * observation's recorded time.
   */
  private List<Job> replay(String name, boolean requeueOnPreempt) {
    SlurmScenario scenario = SlurmScenario.load(name);
    createRunDir();
    JobPoller poller =
        new JobPoller(
            executor(requeueOnPreempt), new ReportParser(new MetricCalculator()), clock);
    JobHandle handle =
        JobHandle.of(SlurmExecutor.NAME, scenario.jobId(), runDir(), SUBMITTED_AT,
            Optional.empty());
    Job job = Job.created("j1", spec, SUBMITTED_AT).submitted(handle, SUBMITTED_AT);
    List<Job> after = new ArrayList<>();
    for (SlurmScenario.Observation o : scenario.observations()) {
      clock.set(o.at());
      commands.observe(o.results());
      job = poller.poll(job).job();
      after.add(job);
    }
    return after;
  }

  private static List<JobState> states(List<Job> jobs) {
    List<JobState> out = new ArrayList<>();
    jobs.forEach(j -> out.add(j.state()));
    return out;
  }

  private static Job last(List<Job> jobs) {
    return jobs.get(jobs.size() - 1);
  }

  private static Instant local(String isoWithOffset) {
    return java.time.OffsetDateTime.parse(isoWithOffset).toInstant();
  }

  // --- ARCHITECTURE.md rows ---------------------------------------------------------------------

  @Test
  public void pendingAndConfiguringAreSubmitted() {
    assertEquals(List.of(JobState.SUBMITTED), states(replay("synthetic_pending", true)));
    assertEquals(List.of(JobState.SUBMITTED), states(replay("synthetic_configuring", true)));
  }

  @Test
  public void runningAndCompletingAreRunningFromSlurmsStartTime() {
    List<Job> jobs = replay("synthetic_running_states", true);
    for (Job job : jobs) {
      assertEquals(JobState.RUNNING, job.state());
    }
    assertEquals(Optional.of(local("2026-10-01T12:00:05-04:00")), last(jobs).runningSince());
    assertEquals("one RUNNING entry, however many polls", 3, last(jobs).history().size());
  }

  @Test
  public void completedSucceedsAfterReportValidationWithSacctTimes() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("synthetic_completed", true);
    assertEquals(List.of(JobState.RUNNING, JobState.SUCCEEDED), states(jobs));
    assertEquals(local("2026-10-01T12:20:00-04:00"), last(jobs).lastChange().at());
    assertTrue(Files.notExists(runDir().resolve(SlurmExecutor.UNRESOLVED_FILE)));
  }

  @Test
  public void completedWithoutAValidReportFailsAsInvalidReport() throws IOException {
    harnessExited(0);
    Files.delete(spec.reportPath(1));
    Job job = last(replay("synthetic_completed", true));
    assertEquals(Optional.of(FailureReason.INVALID_REPORT), job.failureReason());
  }

  @Test
  public void failedTakesTheContractReasonOfItsExitCode() throws IOException {
    harnessExited(3);
    Job job = last(replay("synthetic_failed_exit_3", true));
    assertEquals(Optional.of(FailureReason.MODEL_LOAD), job.failureReason());
    assertTrue(job.lastChange().cause(), job.lastChange().cause().contains("code 3"));
  }

  @Test
  public void failedBySignalIsAnUnexpectedExit() {
    Job job = last(replay("synthetic_failed_signal", true));
    assertEquals(Optional.of(FailureReason.UNEXPECTED_EXIT), job.failureReason());
    assertTrue(
        job.lastChange().cause(), job.lastChange().cause().contains("FAILED, exit code 0:9"));
  }

  @Test
  public void outOfMemoryTimeoutDeadlineNodeFailAndBootFailHaveTheirReasons() {
    Object[][] rows = {
      {"synthetic_out_of_memory", FailureReason.OUT_OF_MEMORY, true},
      {"synthetic_timeout", FailureReason.TIMEOUT, true},
      {"synthetic_deadline", FailureReason.TIMEOUT, false},
      {"synthetic_node_fail", FailureReason.NODE_FAILURE, true},
      {"synthetic_boot_fail", FailureReason.NODE_FAILURE, false},
    };
    for (Object[] row : rows) {
      setUp();
      Job job = last(replay((String) row[0], true));
      assertEquals((String) row[0], Optional.of(row[1]), job.failureReason());
      assertEquals(
          row[0] + ": the history shows whether it ran",
          row[2],
          job.history().stream().anyMatch(c -> c.to() == JobState.RUNNING));
    }
  }

  @Test
  public void cancelledWithAnySuffixIsCancelled() {
    Job job = last(replay("synthetic_cancelled", true));
    assertEquals(JobState.CANCELLED, job.state());
    assertTrue(job.lastChange().cause(), job.lastChange().cause().contains("CANCELLED by 1001"));
  }

  @Test
  public void preemptRequeueCompleteRunsTwiceAndSucceeds() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("synthetic_preempt_requeue_complete", true);
    assertEquals(
        List.of(
            JobState.RUNNING,
            JobState.SUBMITTED,
            JobState.SUBMITTED,
            JobState.RUNNING,
            JobState.SUCCEEDED),
        states(jobs));
    Job done = last(jobs);
    assertEquals("one attempt throughout", 1, done.attempt());
    assertEquals(
        "provenance starts at the run that finished",
        Optional.of(local("2026-10-01T12:13:20-04:00")),
        done.runningSince());
    assertTrue(commands.calls("scancel").isEmpty());
  }

  @Test
  public void requeueWithoutRequeueOnPreemptFailsAndCancelsTheRequeuedJob() {
    commands.always(
        dev.draftwatch.exec.slurm.CommandResult.of(
            List.of("scancel", SlurmScenario.load("synthetic_preempt_requeue_complete").jobId()),
            0, "", ""));
    List<Job> jobs = replay("synthetic_preempt_requeue_complete", false);
    assertEquals(JobState.FAILED, jobs.get(1).state());
    assertEquals(Optional.of(FailureReason.PREEMPTED_NO_REQUEUE), jobs.get(1).failureReason());
    assertEquals(1, commands.calls("scancel").size());
    assertTrue(jobs.get(1).lastChange().cause().contains("cancelled it"));
  }

  @Test
  public void preemptedJobThatLeavesTheQueueWasNotRequeued() {
    List<Job> jobs = replay("synthetic_preempted_not_requeued", true);
    assertEquals(
        List.of(JobState.RUNNING, JobState.SUBMITTED, JobState.FAILED), states(jobs));
    assertEquals(Optional.of(FailureReason.PREEMPTED_NO_REQUEUE), last(jobs).failureReason());
    setUp();
    jobs = replay("synthetic_preempted_not_requeued", false);
    assertEquals(JobState.FAILED, jobs.get(1).state());
    assertEquals(Optional.of(FailureReason.PREEMPTED_NO_REQUEUE), jobs.get(1).failureReason());
  }

  // --- recorded on Explorer, Slurm 23.11.6 ------------------------------------------------------

  @Test
  public void recordedCompletedJobSucceedsWithSlurmsTimes() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("real_10756833_completed", true);
    assertEquals(
        "PENDING, RUNNING, then COMPLETED while squeue still lists it",
        List.of(JobState.SUBMITTED, JobState.RUNNING, JobState.SUCCEEDED),
        states(jobs).subList(0, 3));
    assertEquals(Optional.of(Instant.parse("2026-10-02T07:54:35Z")), last(jobs).runningSince());
    assertEquals(Instant.parse("2026-10-02T07:54:55Z"), last(jobs).lastChange().at());
  }

  @Test
  public void recordedFailedJobTakesTheReasonOfExitCode3() throws IOException {
    harnessExited(3);
    Job job = last(replay("real_10756834_failed_exit_3", true));
    assertEquals(Optional.of(FailureReason.MODEL_LOAD), job.failureReason());
    assertEquals(Optional.of(Instant.parse("2026-10-02T07:54:35Z")), job.runningSince());
  }

  @Test
  public void recordedTimeoutFailsAsTimeout() {
    Job job = last(replay("real_10756837_timeout", true));
    assertEquals(Optional.of(FailureReason.TIMEOUT), job.failureReason());
    assertTrue(job.lastChange().cause(), job.lastChange().cause().contains("TIMEOUT"));
  }

  @Test
  public void recordedCancellationsBeforeAndWhileRunningAreCancelled() {
    Job pending = last(replay("real_10756835_cancelled_pending", true));
    assertEquals(JobState.CANCELLED, pending.state());
    assertTrue(
        "sacct's Start is None: it never ran",
        pending.history().stream().noneMatch(c -> c.to() == JobState.RUNNING));
    setUp();
    Job running = last(replay("real_10756836_cancelled_running", true));
    assertEquals(JobState.CANCELLED, running.state());
    assertTrue(running.lastChange().cause(), running.lastChange().cause().contains("CANCELLED by"));
  }

  @Test
  public void recordedRequeueRunsTwiceAndSucceedsFromTheSecondStart() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("real_10756839_requeued", true);
    assertEquals(JobState.RUNNING, jobs.get(1).state());
    assertEquals("scontrol requeue: back to PENDING", JobState.SUBMITTED, jobs.get(2).state());
    assertEquals(JobState.SUCCEEDED, last(jobs).state());
    assertEquals(1, last(jobs).attempt());
    assertEquals(Optional.of(Instant.parse("2026-10-02T07:57:17Z")), last(jobs).runningSince());
  }

  @Test
  public void recordedNodeFailureThatSlurmRequeuedEndsAsItsLatestRecord() throws IOException {
    harnessExited(0);
    Job job = last(replay("real_6731116_history_node_fail", true));
    assertEquals(
        "sacct without --duplicates shows the COMPLETED record after the NODE_FAIL one",
        JobState.SUCCEEDED,
        job.state());
  }

  @Test
  public void recordedMemoryOverrunWasNotEnforcedAndCompleted() throws IOException {
    harnessExited(0); // Explorer let a --mem=64M job allocate 1 GiB
    assertEquals(
        JobState.SUCCEEDED, last(replay("real_10756838_out_of_memory", true)).state());
  }

  // --- the other documented states --------------------------------------------------------------

  @Test
  public void heldStatesStaySubmitted() {
    for (Job job : replay("synthetic_held", true)) {
      assertEquals(JobState.SUBMITTED, job.state());
    }
  }

  // --- unresolved observations ------------------------------------------------------------------

  @Test
  public void jobBrieflyInNeitherSqueueNorSacctIsWaitedFor() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("synthetic_briefly_in_neither", true);
    assertEquals(
        List.of(JobState.RUNNING, JobState.RUNNING, JobState.RUNNING, JobState.SUCCEEDED),
        states(jobs));
    assertEquals(2, warnings.size());
    assertTrue(warnings.get(1), warnings.get(1).contains("in neither squeue nor sacct"));
    assertTrue(warnings.get(1), warnings.get(1).contains("poll 2"));
    assertTrue(
        "resolved: the record is removed",
        Files.notExists(runDir().resolve(SlurmExecutor.UNRESOLVED_FILE)));
  }

  @Test
  public void jobInNeitherForFiveMinutesFails() {
    List<Job> jobs = replay("synthetic_lost", true);
    assertEquals(
        List.of(JobState.RUNNING, JobState.RUNNING, JobState.RUNNING, JobState.FAILED),
        states(jobs));
    Job job = last(jobs);
    assertEquals(Optional.of(FailureReason.UNEXPECTED_EXIT), job.failureReason());
    assertTrue(
        job.lastChange().cause(),
        job.lastChange().cause().contains("in neither squeue nor sacct for 300 s (3 polls)"));
    assertTrue("nothing to cancel", commands.calls("scancel").isEmpty());
  }

  @Test
  public void unknownStateIsUnresolvedThenFailsAndIsCancelled() {
    List<Job> jobs = replay("synthetic_unknown_state", true);
    assertEquals(
        List.of(JobState.SUBMITTED, JobState.SUBMITTED, JobState.FAILED), states(jobs));
    assertTrue(last(jobs).lastChange().cause(),
        last(jobs).lastChange().cause().contains("unknown state EXPEDITING"));
    assertEquals(1, commands.calls("scancel").size());
  }

  @Test
  public void sacctLagWhileSqueueNoLongerListsTheJobIsWaitedFor() throws IOException {
    harnessExited(0);
    List<Job> jobs = replay("synthetic_sacct_lag", true);
    assertEquals(
        List.of(JobState.RUNNING, JobState.RUNNING, JobState.SUCCEEDED), states(jobs));
    assertTrue(warnings.get(0), warnings.get(0).contains("sacct still reports RUNNING"));
  }

  @Test
  public void exitCodeFileNotYetVisibleIsWaitedFor() throws IOException {
    harnessExited(0);
    Path exitFile = runDir().resolve(LocalExecutor.EXIT_FILE);
    Path hidden = runDir().resolve("exit_code.hidden");
    Files.move(exitFile, hidden); // as a stale NFS lookup cache would show it
    SlurmScenario scenario = SlurmScenario.load("synthetic_completed");
    JobPoller poller =
        new JobPoller(executor(true), new ReportParser(new MetricCalculator()), clock);
    Job job =
        Job.created("j1", spec, SUBMITTED_AT)
            .submitted(
                JobHandle.of(SlurmExecutor.NAME, scenario.jobId(), runDir(), SUBMITTED_AT,
                    Optional.empty()),
                SUBMITTED_AT);
    SlurmScenario.Observation done = scenario.observations().get(1);
    clock.set(done.at());
    commands.observe(done.results());
    job = poller.poll(job).job();
    assertEquals("unresolved: nothing changes", JobState.SUBMITTED, job.state());
    Files.move(hidden, exitFile);
    clock.set(done.at().plusSeconds(30));
    assertEquals(JobState.SUCCEEDED, poller.poll(job).job().state());
  }

  @Test
  public void exitCodeFileThatDisagreesWithSacctFails() throws IOException {
    harnessExited(0);
    Files.writeString(runDir().resolve(LocalExecutor.EXIT_FILE), "3\n");
    Job job = last(replay("synthetic_completed", true));
    assertEquals(Optional.of(FailureReason.UNEXPECTED_EXIT), job.failureReason());
    assertTrue(job.lastChange().cause(), job.lastChange().cause().contains("says '3'"));
  }

  @Test
  public void squeueWithoutALineForTheJobMeansItLeftTheQueue() throws IOException {
    harnessExited(0);
    assertEquals(
        List.of(JobState.RUNNING, JobState.SUCCEEDED),
        states(replay("synthetic_gone_with_empty_output", true)));
  }

  @Test
  public void squeueFailingOtherwiseIsAnErrorAndChangesNothing() {
    try {
      replay("synthetic_squeue_error", true);
      fail("expected ExecutorException");
    } catch (ExecutorException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Unable to contact slurm controller"));
    }
    assertFalse(Files.exists(runDir().resolve(SlurmExecutor.UNRESOLVED_FILE)));
  }

  @Test
  public void everyQueryRunsWithTheTimeFormatAndWithoutFormatOverrides() {
    replay("synthetic_completed", true);
    for (ReplayCommandRunner.Call call : commands.calls()) {
      assertEquals(Map.of("SLURM_TIME_FORMAT", SlurmCli.TIME_FORMAT), call.set());
      assertEquals(SlurmCli.FORMAT_VARIABLES, call.unset());
    }
  }

  @Test
  public void everyFixtureSaysWhereItCameFrom() throws IOException {
    try (java.util.stream.Stream<Path> files = Files.list(SlurmScenario.dir())) {
      for (Path f : (Iterable<Path>) files::iterator) {
        String name = f.getFileName().toString().replaceFirst("\\.json$", "");
        assertTrue(name, name.startsWith("synthetic_") || name.matches("real_\\d+_.+"));
        SlurmScenario s = SlurmScenario.load(name);
        assertTrue(name, s.source().startsWith(s.isRecorded() ? "recorded" : "synthetic"));
      }
    }
  }
}
