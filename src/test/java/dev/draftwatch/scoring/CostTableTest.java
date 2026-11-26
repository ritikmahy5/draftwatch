package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.testing.CountsMeasurements;
import dev.draftwatch.testing.FakeHarness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Measurement cost, on {@code synthetic_scoring_sacct.txt} and invented wall-clock times. */
public class CostTableTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static Map<String, Duration> dump() {
    return SacctDump.parse(FakeHarness.fixture("synthetic_scoring_sacct.txt"));
  }

  @Test
  public void parsesElapsedFormats() {
    Map<String, Duration> d = dump();
    assertEquals(Duration.ofSeconds(150), d.get("draftwatch-j-a-a1"));
    assertEquals(Duration.ofSeconds(370), d.get("draftwatch-j-b-a1"));
    assertEquals(Duration.ofSeconds(86410), d.get("draftwatch-j-x-a1"));
    assertFalse("only COMPLETED rows", d.containsKey("draftwatch-j-c-a1"));
    assertEquals(4, d.size());
  }

  private Path file(String content) throws IOException {
    Path p = tmp.newFile().toPath();
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }

  @Test
  public void malformedLineNamesItsNumber() throws IOException {
    Path p = file("JobName|Elapsed|State\na|00:01:00|COMPLETED\nb|soon|COMPLETED\n");
    DataException e = assertThrows(DataException.class, () -> SacctDump.parse(p));
    assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
  }

  @Test
  public void wrongHeaderFails() throws IOException {
    Path p = file("JobName|State\na|COMPLETED\n");
    DataException e = assertThrows(DataException.class, () -> SacctDump.parse(p));
    assertTrue(e.getMessage(), e.getMessage().contains("line 1"));
  }

  @Test
  public void duplicateJobNameFails() throws IOException {
    Path p = file("JobName|Elapsed|State\na|00:01:00|COMPLETED\na|00:02:00|COMPLETED\n");
    DataException e = assertThrows(DataException.class, () -> SacctDump.parse(p));
    assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
  }

  private static Measurement m(String job, int numPrompts, double wallClock) {
    return new CountsMeasurements(4)
        .numPrompts(numPrompts)
        .wallClockSeconds(wallClock)
        .measurement(job, 100, ScoringFixtures.T0, ScoringFixtures.prompts(4, 0));
  }

  private static CostTable table() {
    List<Measurement> all =
        List.of(m("j-a", 1000, 300), m("j-b", 1000, 340), m("j-d", 100, 40), m("j-e", 100, 60));
    List<AcceptanceReport> pilot =
        List.of(m("p-1", 200, 100).report(), m("p-2", 200, 120).report());
    return CostTable.of(all, dump(), pilot);
  }

  private static CostRow row(CostTable t, int n) {
    return t.rows().stream().filter(r -> r.n() == n).findFirst().get();
  }

  @Test
  public void rowsForMeasuredSizes() {
    CostTable t = table();
    assertEquals(List.of(25, 50, 100, 200, 1000), t.rows().stream().map(CostRow::n)
        .collect(java.util.stream.Collectors.toList()));
    CostRow full = row(t, 1000);
    assertFalse(full.extrapolated());
    assertEquals(OptionalDouble.of(320.0), full.meanWallClockSeconds());
    assertEquals(OptionalDouble.of(260.0), full.meanElapsedSeconds());
    assertEquals(2, full.measurements());
    CostRow live = row(t, 100);
    assertEquals(OptionalDouble.of(50.0), live.meanWallClockSeconds());
    assertEquals(OptionalDouble.of(50.0), live.meanElapsedSeconds()); // j-e has no sacct row
    CostRow pilot = row(t, 200);
    assertEquals(OptionalDouble.of(110.0), pilot.meanWallClockSeconds());
    assertFalse(pilot.meanElapsedSeconds().isPresent());
    assertEquals(2, pilot.measurements());
    assertEquals(Set.of("fake-0.1.0"), pilot.harnessVersions());
  }

  @Test
  public void extrapolatesSmallSizes() {
    CostTable t = table();
    // Wall clock: least squares through (100, 50), (200, 110), (1000, 320):
    // slope = 417000 / 1460000, intercept = (480 - slope * 1300) / 3.
    double slope = 417000.0 / 1460000.0;
    double intercept = (480 - slope * 1300) / 3;
    CostRow n25 = row(t, 25);
    assertTrue(n25.extrapolated());
    assertEquals(intercept + slope * 25, n25.meanWallClockSeconds().getAsDouble(), 1e-12);
    assertEquals(intercept + slope * 50, row(t, 50).meanWallClockSeconds().getAsDouble(), 1e-12);
    // Elapsed: the line through (100, 50) and (1000, 260).
    assertEquals(32.5, n25.meanElapsedSeconds().getAsDouble(), 1e-12);
    assertEquals(0, n25.measurements());
    assertTrue(n25.harnessVersions().isEmpty());
  }

  @Test
  public void listsJobsMissingFromDump() {
    assertEquals(List.of("draftwatch-j-e-a1"), table().missingElapsed());
  }
}
