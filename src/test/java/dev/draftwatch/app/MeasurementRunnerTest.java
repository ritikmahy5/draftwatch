package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.events.MeasurementStored;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobIds;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.exec.RetryPolicy;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.FakeExecutor;
import dev.draftwatch.testing.InMemoryJobRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.ScriptedExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The job lifecycle end to end with {@link FakeExecutor} and the fake harness. */
public class MeasurementRunnerTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Sleeper NO_WAIT = duration -> {};

  private final InMemoryJobRepository jobs = new InMemoryJobRepository();
  private final InMemoryResultRepository results = new InMemoryResultRepository();
  private final List<MeasurementStored> stored = new ArrayList<>();
  private final EventBus bus = new EventBus(message -> {}, Clock.systemUTC());
  private Path state;
  private ReportScenario scenario;

  @Before
  public void setUp() {
    bus.subscribe(MeasurementStored.class, "recorder", stored::add);
    Path dir = tmp.getRoot().toPath();
    state = dir.resolve("state");
    scenario = ReportScenario.sampledAdapter(dir);
  }

  private MeasurementRunner runner(Executor executor, int maxRetries, Map<String, String> env) {
    int[] counter = {0};
    JobIds ids = () -> "job-" + (++counter[0]);
    return new MeasurementRunner(
        executor,
        new JobPoller(executor, new ReportParser(new MetricCalculator()), Clock.systemUTC()),
        new RetryPolicy(maxRetries),
        jobs,
        results,
        bus,
        ids,
        Clock.systemUTC(),
        state,
        scenario.harnessCommand(env),
        scenario.dir());
  }

  private Job run(MeasurementRunner runner) throws InterruptedException {
    Job job = runner.submit(runner.create(scenario.checkpoint(), scenario.probe()));
    return runner.runToCompletion(List.of(job), NO_WAIT).get(0);
  }

  @Test
  public void successfulJobStoresAMeasurementWithEveryProvenanceField() throws Exception {
    FakeExecutor executor = new FakeExecutor(Clock.systemUTC());
    Job job = run(runner(executor, 0, Map.of()));
    assertEquals(JobState.SUCCEEDED, job.state());
    assertEquals(Optional.of(job), jobs.find(job.id()));
    Measurement m = results.all().get(0);
    Provenance p = m.provenance();
    assertEquals(scenario.checkpoint(), p.checkpoint());
    assertTrue(p.checkpoint().baseModel().isPresent());
    assertEquals(scenario.probe().probe().id(), p.probeId());
    assertEquals(scenario.probe().hash(), p.probeHash());
    assertEquals(scenario.probe().probe().draft().id(), p.draftId());
    assertEquals(scenario.probe().draftFingerprint(), p.draftFingerprint());
    assertEquals("fake-0.1.0", p.harnessVersion());
    assertEquals("fake", p.backend());
    assertEquals("bfloat16", p.dtype());
    assertEquals(scenario.probe().probe().estimator(), p.estimator());
    assertEquals(List.of(3, 5), p.seeds());
    assertEquals(scenario.probe().promptSet().sha256(), p.promptSetSha256());
    assertEquals(FakeExecutor.NAME, p.executor());
    assertEquals("job-1", p.jobId());
    assertEquals(1, p.attempt());
    assertEquals(job.handle().get().submittedAt(), p.startTime());
    assertEquals(job.lastChange().at(), p.endTime());
    assertEquals(state.resolve("raw/job-1/attempt-1/report.json"), p.rawReportPath());
    assertTrue(Files.isRegularFile(p.rawReportPath()));
    assertEquals(1, stored.size());
    assertEquals(m, stored.get(0).measurement());
    assertEquals(results.locate(m), stored.get(0).resultFile());
  }

  @Test
  public void eachAttemptRecordsItsExactInvocation() throws Exception {
    run(runner(new FakeExecutor(Clock.systemUTC()), 0, Map.of()));
    String invocation = Files.readString(state.resolve("raw/job-1/attempt-1/invocation.json"));
    assertTrue(invocation, invocation.contains("--out"));
    assertTrue(invocation, invocation.contains("attempt-1/report.json"));
  }

  // --- exit codes 2-5 are not retried; an unexpected code is, up to max_retries ---

  @Test
  public void contractExitCodesAreNotRetried() throws Exception {
    FailureReason[] reasons = {
      FailureReason.BAD_ARGUMENTS,
      FailureReason.MODEL_LOAD,
      FailureReason.OUT_OF_MEMORY,
      FailureReason.BACKEND_COUNTERS
    };
    for (int code = 2; code <= 5; code++) {
      FakeExecutor executor = new FakeExecutor(Clock.systemUTC());
      Job job =
          run(runner(executor, 3, Map.of("DRAFTWATCH_FAKE_EXIT", Integer.toString(code))));
      assertEquals("exit " + code, JobState.FAILED, job.state());
      assertEquals(Optional.of(reasons[code - 2]), job.failureReason());
      assertEquals("exit " + code + " is attempted once", 1, job.attempt());
      assertEquals(1, executor.submitted().size());
    }
    assertTrue(results.all().isEmpty());
  }

  @Test
  public void unexpectedExitCodeIsRetriedExactlyMaxRetriesTimes() throws Exception {
    FakeExecutor executor = new FakeExecutor(Clock.systemUTC());
    Job job = run(runner(executor, 2, Map.of("DRAFTWATCH_FAKE_EXIT", "1")));
    assertEquals(JobState.FAILED, job.state());
    assertEquals(Optional.of(FailureReason.UNEXPECTED_EXIT), job.failureReason());
    assertEquals("1 attempt + 2 retries", 3, job.attempt());
    assertEquals(3, executor.submitted().size());
    for (int attempt = 1; attempt <= 3; attempt++) {
      assertTrue(Files.isDirectory(state.resolve("raw/job-1/attempt-" + attempt)));
    }
    long retries =
        job.history().stream().filter(c -> c.cause().startsWith("retry after")).count();
    assertEquals(2, retries);
  }

  @Test
  public void refusedSubmissionFailsTheJobWithoutRetry() throws Exception {
    Job job = run(runner(new ScriptedExecutor().failSubmissions(), 5, Map.of()));
    assertEquals(Optional.of(FailureReason.SUBMISSION_FAILED), job.failureReason());
    assertEquals(1, job.attempt());
  }

  @Test
  public void advancingAJobWhoseSaveWasLostDoesNotDuplicateItsResult() throws Exception {
    MeasurementRunner runner = runner(new FakeExecutor(Clock.systemUTC()), 0, Map.of());
    Job submitted = runner.submit(runner.create(scenario.checkpoint(), scenario.probe()));
    Job succeeded = runner.advance(submitted);
    assertEquals(JobState.SUCCEEDED, succeeded.state());
    // As if draftwatch crashed after storing the result but before saving the job:
    Job again = runner.advance(submitted);
    assertEquals(succeeded, again);
    assertEquals(1, results.all().size());
    assertEquals("published again; detection skips what is already recorded", 2, stored.size());
  }

  @Test
  public void resultOfASucceededJobIsFoundAndLocated() throws Exception {
    MeasurementRunner runner = runner(new FakeExecutor(Clock.systemUTC()), 0, Map.of());
    Job job = run(runner);
    Measurement m = runner.result(job).orElseThrow();
    assertEquals(job.id(), m.jobId());
    assertEquals(Path.of("memory", job.id() + ".json"), runner.locate(m));
  }
}
