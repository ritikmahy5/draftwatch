package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SyntheticMeasurements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileResultRepositoryTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private Path state;
  private FileResultRepository repo;
  private ReportScenario scenario;
  private AcceptanceReport report;

  @Before
  public void setUp() throws IOException {
    Path root = tmp.getRoot().toPath();
    state = root.resolve("state");
    repo = new FileResultRepository(state, new JsonCodec(), new ObjectMapper());
    scenario = ReportScenario.sampledAdapter(Files.createDirectories(root.resolve("s")));
    report = SyntheticMeasurements.report(scenario);
  }

  private Measurement m(String jobId, long step, Instant end) {
    return SyntheticMeasurements.measurement(scenario, report, jobId, step, end);
  }

  private static List<String> jobIds(List<Measurement> ms) {
    return ms.stream().map(Measurement::jobId).collect(Collectors.toList());
  }

  @Test
  public void resultsRoundTripExactly() {
    Measurement m = m("j-1", 100, T0);
    repo.append(m);
    assertEquals(Optional.of(m), repo.latest(m.fingerprint(), m.probeHash()));
  }

  @Test
  public void fileNameIsFingerprintProbeHashAndJobId() {
    Measurement m = m("j-1", 100, T0);
    repo.append(m);
    Path expected =
        state.resolve("results/run/" + m.fingerprint() + "__" + m.probeHash() + "__j-1.json");
    assertEquals(expected, repo.locate(m));
    assertTrue(Files.isRegularFile(expected));
  }

  @Test
  public void appendingTheIdenticalResultAgainIsANoOp() {
    Measurement m = m("j-1", 100, T0);
    repo.append(m);
    repo.append(m);
    assertEquals(1, repo.find(m.fingerprint(), m.probeHash()).size());
  }

  @Test
  public void aDifferentResultForTheSameJobIsRefused() throws IOException {
    Measurement first = m("j-1", 100, T0);
    repo.append(first);
    byte[] before = Files.readAllBytes(repo.locate(first));
    Measurement other = m("j-1", 100, T0.plusSeconds(1));
    try {
      repo.append(other);
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("append-only"));
    }
    assertEquals(new String(before), Files.readString(repo.locate(first)));
  }

  @Test
  public void historyIsInStepOrderThenEndTime() {
    repo.append(m("j-c", 300, T0));
    repo.append(m("j-a", 100, T0.plusSeconds(50)));
    repo.append(m("j-b", 100, T0.plusSeconds(10)));
    assertEquals(
        List.of("j-b", "j-a", "j-c"),
        jobIds(repo.history("run", scenario.probe().hash())));
    assertEquals(List.of(), repo.history("run", "f".repeat(64)));
    assertEquals(List.of(), repo.history("other-target", scenario.probe().hash()));
  }

  @Test
  public void findReturnsEveryAttemptOldestFirstAndLatestTheNewest() {
    Measurement older = m("j-2", 100, T0);
    Measurement newer = m("j-1", 100, T0.plusSeconds(60));
    repo.append(newer);
    repo.append(older);
    repo.append(m("j-3", 200, T0));
    assertEquals(
        List.of("j-2", "j-1"), jobIds(repo.find(older.fingerprint(), older.probeHash())));
    assertEquals(Optional.of(newer), repo.latest(older.fingerprint(), older.probeHash()));
    assertEquals(Optional.empty(), repo.latest("sampled-" + "0".repeat(64), older.probeHash()));
  }

  @Test
  public void corruptResultFailsLoudly() throws IOException {
    Measurement m = m("j-1", 100, T0);
    repo.append(m);
    Path file = repo.locate(m);
    Files.writeString(file, Files.readString(file).replace("\"backend\"", "\"backnd\""));
    try {
      repo.find(m.fingerprint(), m.probeHash());
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().startsWith(file.toString()));
    }
  }
}
