package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.exec.RetryPolicy;
import dev.draftwatch.testing.ReportScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileJobRepositoryTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private Path state;
  private FileJobRepository repo;
  private MeasurementSpec full;
  private MeasurementSpec adapter;

  @Before
  public void setUp() throws IOException {
    Path root = tmp.getRoot().toPath();
    state = root.resolve("state");
    repo = new FileJobRepository(state, new JsonCodec(), new ObjectMapper());
    ReportScenario greedy = ReportScenario.greedy(Files.createDirectories(root.resolve("g")));
    ReportScenario sampled =
        ReportScenario.sampledAdapter(Files.createDirectories(root.resolve("s")));
    full = spec(greedy, "j-a");
    adapter = spec(sampled, "j-b");
  }

  private MeasurementSpec spec(ReportScenario s, String id) {
    return MeasurementSpec.of(
        s.checkpoint(),
        s.probe(),
        s.harnessCommand(Map.of()),
        s.dir(),
        state.resolve("raw").resolve(id),
        "local");
  }

  private Job retriedAndRunning(String id, MeasurementSpec spec) {
    JobHandle first =
        JobHandle.of("local", "101", spec.runDir(1), T0, Optional.of(T0.minusMillis(5)));
    JobHandle second = JobHandle.of("local", "202", spec.runDir(2), T0, Optional.empty());
    return Job.created(id, spec, T0)
        .submitted(first, T0)
        .running(T0, "started")
        .failed(FailureReason.UNEXPECTED_EXIT, "harness exited with code 1", T0.plusSeconds(3))
        .retried(new RetryPolicy(2), T0.plusSeconds(4))
        .submitted(second, T0.plusSeconds(5))
        .running(T0.plusSeconds(5), "started");
  }

  @Test
  public void jobsRoundTripWithFullHistory() {
    Job a = retriedAndRunning("j-a", full);
    Job b = Job.created("j-b", adapter, T0);
    repo.save(b);
    repo.save(a);
    assertEquals(Optional.of(a), repo.find("j-a"));
    assertEquals(Optional.of(b), repo.find("j-b"));
    assertEquals(List.of(a, b), repo.all());
    assertTrue(Files.isRegularFile(state.resolve("jobs/j-a.json")));
  }

  @Test
  public void saveReplacesThePreviousValue() {
    Job created = Job.created("j-a", full, T0);
    repo.save(created);
    Job cancelled = created.cancelled(T0, "test");
    repo.save(cancelled);
    assertEquals(Optional.of(cancelled), repo.find("j-a"));
  }

  @Test
  public void missingJobIsEmpty() {
    assertEquals(Optional.empty(), repo.find("nope"));
    assertEquals(List.of(), repo.all());
  }

  @Test
  public void corruptJobFileFailsLoudlyNamingFileAndField() throws IOException {
    repo.save(Job.created("j-a", full, T0));
    Path file = state.resolve("jobs/j-a.json");
    Files.writeString(file, Files.readString(file).replace("\"to\" : \"CREATED\"", "\"to\" : 7"));
    try {
      repo.find("j-a");
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().startsWith(file.toString()));
      assertTrue(e.getMessage(), e.getMessage().contains("history[0].to is not a string"));
    }
  }

  @Test
  public void storedStateMustAgreeWithHistory() throws IOException {
    repo.save(Job.created("j-a", full, T0));
    Path file = state.resolve("jobs/j-a.json");
    Files.writeString(
        file, Files.readString(file).replace("\"state\" : \"CREATED\"", "\"state\" : \"RUNNING\""));
    try {
      repo.find("j-a");
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("disagree with the history"));
    }
  }
}
