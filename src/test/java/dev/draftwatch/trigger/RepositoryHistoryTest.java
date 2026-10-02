package dev.draftwatch.trigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.domain.Measurement;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.testing.InMemoryJobRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SyntheticMeasurements;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RepositoryHistoryTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  public void seesStoredResultsAndJobsLive() {
    Path dir = tmp.getRoot().toPath();
    ReportScenario s = ReportScenario.greedy(dir);
    InMemoryJobRepository jobs = new InMemoryJobRepository();
    InMemoryResultRepository results = new InMemoryResultRepository();
    RepositoryHistory history = new RepositoryHistory(jobs, results);
    String fp = s.checkpoint().fingerprint();
    String hash = s.probe().hash();
    assertFalse(history.hasResult(fp, hash));
    assertEquals(0, history.activeJobs("run"));

    MeasurementSpec spec =
        MeasurementSpec.of(
            s.checkpoint(), s.probe(), s.harnessCommand(Map.of()), dir, dir.resolve("raw/j"), "x");
    Job created = Job.created("j", spec, T0);
    jobs.save(created);
    assertEquals(List.of(created), history.jobs(fp, hash));
    assertEquals(1, history.activeJobs("run"));
    assertEquals(0, history.activeJobs("other"));
    Job done =
        created
            .submitted(JobHandle.of("x", "1", dir, T0, Optional.empty()), T0)
            .running(T0, "started")
            .succeeded(T0);
    jobs.save(done);
    assertEquals(0, history.activeJobs("run"));

    Measurement m =
        SyntheticMeasurements.measurement(s, SyntheticMeasurements.report(s), "j", 100, T0);
    results.append(m);
    assertTrue(history.hasResult(m.fingerprint(), hash));
  }
}
