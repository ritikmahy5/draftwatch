package dev.draftwatch.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.config.RetrainSpec;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.RegressionDetected;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.ExecutorStatus;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobSpec;
import dev.draftwatch.exec.TimestampJobIds;
import dev.draftwatch.store.JsonCodec;
import dev.draftwatch.store.RetrainRequest;
import dev.draftwatch.testing.FakeExecutor;
import dev.draftwatch.testing.InMemoryResultRepository;
import dev.draftwatch.testing.InMemoryRetrainRequestRepository;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SyntheticMeasurements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The training job, its environment, and the one-retrain-per-draft rule (D76, D77). */
public class RetrainDraftActionTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
  /** A training command that records the environment draftwatch gives it. */
  private static final List<String> TRAIN =
      List.of(
          "/bin/sh", "-c", "env | grep '^DRAFTWATCH_' | sort > \"$DRAFTWATCH_RETRAIN_DIR/env\"");

  private final InMemoryResultRepository results = new InMemoryResultRepository();
  private final InMemoryRetrainRequestRepository requests = new InMemoryRetrainRequestRepository();
  private final List<String> console = new ArrayList<>();
  private ReportScenario scenario;
  private AcceptanceReport report;
  private Path state;
  private Path work;

  @Before
  public void setUp() throws IOException {
    Path root = tmp.getRoot().toPath();
    scenario = ReportScenario.greedy(Files.createDirectories(root.resolve("s")));
    report = SyntheticMeasurements.report(scenario);
    state = root.resolve("state");
    work = Files.createDirectories(root.resolve("work"));
  }

  private RetrainDraftAction action(Executor executor) {
    return new RetrainDraftAction(
        "run",
        RetrainSpec.of(TRAIN),
        executor,
        results,
        requests,
        id -> Optional.of(scenario.probe().probe()),
        new TimestampJobIds(CLOCK, new Random(7)),
        state,
        work,
        console::add);
  }

  /** A stored result of job {@code job} at {@code step}, with the given draft fingerprint. */
  private Measurement stored(String job, long step, String draftFingerprint) {
    Measurement m = SyntheticMeasurements.measurement(scenario, report, job, step, T0);
    JsonCodec codec = new JsonCodec();
    ObjectNode json = codec.measurementJson(m);
    ((ObjectNode) json.get("provenance")).put("draft_fingerprint", draftFingerprint);
    Measurement changed = codec.measurement(json);
    results.append(changed);
    return changed;
  }

  private static RegressionDetected regression(Measurement m) {
    return new RegressionDetected(
        T0,
        DetectionSubject.of(m, Path.of("/state/results/run/" + m.jobId() + ".json")),
        DetectorVerdict.builder(
                DetectorVerdict.Kind.REGRESSION, "paired_bootstrap(…)", Metric.ALPHA)
            .observed(-0.1)
            .threshold(-0.0)
            .interval(-0.2, -0.05)
            .explanation("synthetic")
            .build());
  }

  @Test
  public void submitsTheCommandWithTheRegressionsContextInItsEnvironment() throws IOException {
    FakeExecutor executor = new FakeExecutor(CLOCK);
    Measurement m = stored("j-300", 300, "sampled-" + "a".repeat(64));
    action(executor).execute(regression(m));

    assertEquals(1, executor.submitted().size());
    JobSpec job = executor.submitted().get(0);
    assertTrue(job.jobId(), job.jobId().startsWith("retrain-j"));
    assertEquals(work, job.workingDir());
    assertEquals(state.resolve("retrain").resolve(job.jobId()), job.runDir());
    assertEquals("env", job.command().get(0));
    assertEquals(TRAIN, job.command().subList(job.command().size() - 3, job.command().size()));
    List<String> env = Files.readAllLines(job.runDir().resolve("env"));
    assertTrue(env.toString(), env.contains("DRAFTWATCH_TARGET=run"));
    assertTrue(env.toString(), env.contains("DRAFTWATCH_PROBE=" + m.provenance().probeId()));
    assertTrue(env.toString(), env.contains("DRAFTWATCH_CHECKPOINT_STEP=300"));
    assertTrue(
        env.toString(),
        env.contains("DRAFTWATCH_CHECKPOINT=" + m.provenance().checkpoint().path()));
    assertTrue(env.toString(), env.contains("DRAFTWATCH_DRAFT_ID=" + m.provenance().draftId()));
    assertTrue(
        env.toString(),
        env.contains("DRAFTWATCH_DRAFT_PATH=" + scenario.probe().probe().draft().path()));
    assertTrue(
        env.toString(), env.contains("DRAFTWATCH_DRAFT_FINGERPRINT=sampled-" + "a".repeat(64)));
    assertTrue(
        env.toString(), env.contains("DRAFTWATCH_RESULT_FILE=/state/results/run/j-300.json"));
    assertTrue(env.toString(), env.contains("DRAFTWATCH_RETRAIN_DIR=" + job.runDir()));
    List<String> recorded = new ArrayList<>();
    new ObjectMapper()
        .readTree(job.runDir().resolve(RetrainDraftAction.INVOCATION_FILE).toFile())
        .forEach(n -> recorded.add(n.textValue()));
    assertEquals(job.command(), recorded);

    RetrainRequest r = requests.all().get(0);
    assertEquals(job.jobId(), r.retrainId());
    assertEquals("j-300", r.triggeredByJob());
    assertEquals(300, r.checkpointStep());
    assertTrue(console.get(0), console.get(0).contains("submitted training job " + job.jobId()));
    assertTrue(console.get(0), console.get(0).contains("does not deploy the new draft"));
  }

  @Test
  public void aDraftIsRetrainedOnceUntilItsFingerprintChanges() {
    FakeExecutor executor = new FakeExecutor(CLOCK);
    RetrainDraftAction action = action(executor);
    action.execute(regression(stored("j-300", 300, "sampled-" + "a".repeat(64))));
    action.execute(regression(stored("j-400", 400, "sampled-" + "a".repeat(64))));
    assertEquals(
        "the same draft regressed again: no second training run", 1, executor.submitted().size());
    assertTrue(console.get(1), console.get(1).contains("was already sent for retraining"));
    assertTrue(console.get(1), console.get(1).contains("after job j-300"));
    action.execute(regression(stored("j-500", 500, "sampled-" + "b".repeat(64))));
    assertEquals("a new draft can be retrained", 2, executor.submitted().size());
    assertEquals(2, requests.all().size());
  }

  @Test
  public void aRefusedSubmissionRecordsNothingSoTheNextRegressionTriesAgain() {
    Executor refusing =
        new Executor() {
          @Override
          public String name() {
            return "refusing";
          }

          @Override
          public JobHandle submit(JobSpec spec) {
            throw new ExecutorException("sbatch refused the job: Invalid partition name");
          }

          @Override
          public ExecutorStatus status(JobHandle handle) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void cancel(JobHandle handle) {}
        };
    Measurement m = stored("j-300", 300, "sampled-" + "a".repeat(64));
    try {
      action(refusing).execute(regression(m));
      fail("expected ActionFailedException");
    } catch (ActionFailedException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Invalid partition name"));
    }
    assertEquals(List.of(), requests.all());
    FakeExecutor executor = new FakeExecutor(CLOCK);
    action(executor).execute(regression(m));
    assertEquals(1, executor.submitted().size());
  }

  @Test
  public void otherTargetsAndMissingResultsAreHandled() {
    FakeExecutor executor = new FakeExecutor(CLOCK);
    Measurement m = stored("j-300", 300, "sampled-" + "a".repeat(64));
    RegressionDetected elsewhere =
        new RegressionDetected(
            T0,
            DetectionSubject.of(
                "other", "chat", m.probeHash(), 300, m.fingerprint(), "j-300", Path.of("/r.json")),
            regression(m).verdict().orElseThrow());
    action(executor).execute(elsewhere);
    assertEquals(0, executor.submitted().size());
    RegressionDetected unknownJob =
        new RegressionDetected(
            T0,
            DetectionSubject.of(
                "run", "chat", m.probeHash(), 300, m.fingerprint(), "j-gone", Path.of("/r.json")),
            regression(m).verdict().orElseThrow());
    try {
      action(executor).execute(unknownJob);
      fail("expected ActionFailedException");
    } catch (ActionFailedException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("no stored result for job j-gone"));
    }
  }
}
