package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.events.DetectionDeferred;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BaselineAndDetectionLogTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private Path state;

  @Before
  public void setUp() {
    state = tmp.getRoot().toPath().resolve("state");
  }

  // --- baselines -----------------------------------------------------------------------------

  @Test
  public void baselinesRoundTripPerTarget() {
    FileBaselineRepository repo = new FileBaselineRepository(state, json);
    assertEquals(Optional.empty(), repo.get("run"));
    Baseline a =
        Baseline.of("run", "sampled-a", Paths.get("/r/checkpoint-0"), 0, T0, Baseline.Source.AUTO);
    Baseline b =
        Baseline.of(
            "other", "sampled-b", Paths.get("/o/checkpoint-5"), 5, T0, Baseline.Source.MANUAL);
    repo.set(a);
    repo.set(b);
    assertEquals(Optional.of(a), repo.get("run"));
    assertEquals(Optional.of(b), repo.get("other"));
    Baseline replaced =
        Baseline.of(
            "run",
            "sampled-c",
            Paths.get("/r/checkpoint-9"),
            9,
            T0.plusSeconds(1),
            Baseline.Source.MANUAL);
    repo.set(replaced);
    assertEquals(Optional.of(replaced), new FileBaselineRepository(state, json).get("run"));
    assertEquals(Optional.of(b), repo.get("other"));
  }

  @Test
  public void corruptBaselinesFileFailsLoudly() throws IOException {
    Files.createDirectories(state);
    Files.writeString(
        state.resolve(FileBaselineRepository.FILE), "{\"run\": {\"fingerprint\": 1}}");
    try {
      new FileBaselineRepository(state, json).get("run");
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("run.fingerprint is not a string"));
    }
  }

  // --- detection log -------------------------------------------------------------------------

  private static final DetectionSubject SUBJECT =
      DetectionSubject.of(
          "run", "chat", "a".repeat(64), 300, "sampled-x", "j3", Paths.get("/s/results/r.json"));

  @Test
  public void detectionRecordsRoundTripExactly() {
    FileDetectionLog log = new FileDetectionLog(state, json);
    DetectionRecord regression =
        DetectionRecord.of(
            DetectionEvent.of(
                T0,
                SUBJECT,
                DetectorVerdict.builder(
                        DetectorVerdict.Kind.REGRESSION, "paired_bootstrap(…)", Metric.ALPHA)
                    .observed(-0.07051282051282048)
                    .threshold(-0.0)
                    .interval(-0.08901803359683802, -0.050438135780628036)
                    .baselineJobId("j1")
                    .explanation("95% interval …")
                    .build()));
    DetectionRecord deferred =
        DetectionRecord.of(new DetectionDeferred(T0.plusSeconds(1), SUBJECT, "baseline missing"));
    log.append(regression);
    log.append(deferred);
    assertEquals(List.of(regression, deferred), new FileDetectionLog(state, json).all());
    assertEquals(Optional.empty(), deferred.detector());
    assertEquals("baseline missing", deferred.explanation());
  }

  @Test
  public void malformedLineIsReportedWithItsNumber() throws IOException {
    FileDetectionLog log = new FileDetectionLog(state, json);
    log.append(DetectionRecord.of(new DetectionDeferred(T0, SUBJECT, "x")));
    Files.writeString(
        state.resolve(FileDetectionLog.FILE), "{\"at\": 3}\n", StandardOpenOption.APPEND);
    try {
      log.all();
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("line 2 is not a detection record"));
    }
  }
}
