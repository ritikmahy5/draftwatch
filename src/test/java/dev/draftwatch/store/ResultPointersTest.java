package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SyntheticMeasurements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Every pointer resolves, in a file the codec really writes, to the value it names. */
public class ResultPointersTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void pointersNameTheirValuesInAStoredResultFile() throws IOException {
    Path root = tmp.getRoot().toPath();
    ReportScenario scenario =
        ReportScenario.sampledAdapter(Files.createDirectories(root.resolve("s")));
    AcceptanceReport report = SyntheticMeasurements.report(scenario);
    Measurement m =
        SyntheticMeasurements.measurement(
            scenario, report, "j-1", 200, Instant.parse("2026-01-01T00:00:00Z"));
    FileResultRepository repo =
        new FileResultRepository(root.resolve("state"), new JsonCodec(), new ObjectMapper());
    repo.append(m);
    JsonNode file = new ObjectMapper().readTree(repo.locate(m).toFile());

    assertEquals(m.provenance().targetName(), file.at(ResultPointers.TARGET).textValue());
    assertEquals(200, file.at(ResultPointers.CHECKPOINT_STEP).longValue());
    assertEquals(m.provenance().probeId(), file.at(ResultPointers.PROBE_ID).textValue());
    assertEquals(m.probeHash(), file.at(ResultPointers.PROBE_HASH).textValue());
    assertEquals(report.harnessVersion(), file.at(ResultPointers.HARNESS_VERSION).textValue());
    assertEquals(report.backend(), file.at(ResultPointers.BACKEND).textValue());
    assertEquals("j-1", file.at(ResultPointers.JOB_ID).textValue());
    assertEquals(m.provenance().attempt(), file.at(ResultPointers.ATTEMPT).intValue());
    assertEquals(
        m.provenance().endTime().toString(), file.at(ResultPointers.END_TIME).textValue());
    assertEquals(
        report.draftStructure().wireName(), file.at(ResultPointers.DRAFT_STRUCTURE).textValue());
    assertEquals(report.numPrompts(), file.at(ResultPointers.NUM_PROMPTS).intValue());
    assertEquals(
        report.aggregate().alphaMean(), file.at(ResultPointers.ALPHA_MEAN).doubleValue(), 0.0);
    assertEquals(
        report.aggregate().tauMean(), file.at(ResultPointers.TAU_MEAN).doubleValue(), 0.0);
    assertFalse(file.at(ResultPointers.ALPHA_STD).isMissingNode());
    assertFalse(file.at(ResultPointers.TAU_STD).isMissingNode());
    for (int i = 0; i < report.seeds().size(); i++) {
      SeedReport seed = report.seeds().get(i);
      assertEquals(seed.seed(), file.at(ResultPointers.seed(i)).intValue());
      assertEquals(
          seed.positionCountsExact(),
          file.at(ResultPointers.positionCountsExact(i)).booleanValue());
      for (int k = 0; k < seed.positionCounts().size(); k++) {
        assertEquals(
            seed.positionCounts().get(k).position(),
            file.at(ResultPointers.position(i, k)).intValue());
        JsonNode value = file.at(ResultPointers.alphaByPosition(i, k));
        if (seed.alphaByPosition().get(k).isPresent()) {
          assertEquals(seed.alphaByPosition().get(k).getAsDouble(), value.doubleValue(), 0.0);
        } else {
          assertEquals(true, value.isNull());
        }
      }
    }
  }
}
