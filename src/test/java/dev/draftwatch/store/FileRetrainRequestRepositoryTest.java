package dev.draftwatch.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.exec.JobHandle;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileRetrainRequestRepositoryTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

  private FileRetrainRequestRepository repo;

  @Before
  public void setUp() {
    repo = new FileRetrainRequestRepository(tmp.getRoot().toPath(), new ObjectMapper());
  }

  private static RetrainRequest request(String id, String fingerprint, Instant at) {
    return RetrainRequest.of(
        id,
        "run",
        "chat",
        "my-draft",
        fingerprint,
        "j-" + id,
        300,
        Paths.get("/state/results/run/r.json"),
        JobHandle.of("local", "4242", Path.of("/state/retrain/" + id), at, Optional.of(at)));
  }

  @Test
  public void requestsRoundTripExactly() {
    RetrainRequest r = request("retrain-a", "sampled-aa", T0);
    repo.record(r);
    assertEquals(Optional.of(r), repo.find("run", "my-draft", "sampled-aa"));
    assertEquals(Optional.empty(), repo.find("run", "my-draft", "sampled-bb"));
    assertEquals(Optional.empty(), repo.find("other", "my-draft", "sampled-aa"));
  }

  @Test
  public void aSecondRequestForTheSameDraftIsRefused() {
    repo.record(request("retrain-a", "sampled-aa", T0));
    try {
      repo.record(request("retrain-b", "sampled-aa", T0.plusSeconds(1)));
      fail("expected StoreException");
    } catch (StoreException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("already requested"));
    }
    assertEquals("retrain-a", repo.find("run", "my-draft", "sampled-aa").get().retrainId());
  }

  @Test
  public void allIsOldestFirst() {
    repo.record(request("retrain-late", "sampled-bb", T0.plusSeconds(60)));
    repo.record(request("retrain-early", "sampled-aa", T0));
    assertEquals(2, repo.all().size());
    assertEquals("retrain-early", repo.all().get(0).retrainId());
    assertEquals(
        List.of(),
        new FileRetrainRequestRepository(tmp.getRoot().toPath().resolve("none"),
            new ObjectMapper()).all());
  }
}
