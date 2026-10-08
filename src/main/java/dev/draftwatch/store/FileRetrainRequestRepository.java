package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.exec.JobHandle;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Requests as {@code <state>/retrain/requests/<target>__<draft-id>__<draft-fingerprint>.json},
 * created exclusively so a second request for the same draft is refused.
 */
public final class FileRetrainRequestRepository implements RetrainRequestRepository {
  private final Path dir;
  private final ObjectMapper json;

  public FileRetrainRequestRepository(Path stateDir, ObjectMapper json) {
    this.dir = stateDir.resolve("retrain").resolve("requests");
    this.json = Objects.requireNonNull(json, "json");
  }

  private Path file(String target, String draftId, String draftFingerprint) {
    return dir.resolve(target + "__" + draftId + "__" + draftFingerprint + ".json");
  }

  @Override
  public Optional<RetrainRequest> find(String target, String draftId, String draftFingerprint) {
    Path f = file(target, draftId, draftFingerprint);
    try {
      return Optional.of(read(f, Files.readAllBytes(f)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new StoreException(f, "cannot read retrain request: " + e.getMessage(), e);
    }
  }

  @Override
  public void record(RetrainRequest r) {
    Path f = file(r.target(), r.draftId(), r.draftFingerprint());
    try {
      AtomicFiles.writeNew(f, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(toJson(r)));
    } catch (FileAlreadyExistsException e) {
      throw new StoreException(f, "a retrain of this draft was already requested");
    } catch (IOException e) {
      throw new StoreException(f, "cannot write retrain request: " + e.getMessage(), e);
    }
  }

  @Override
  public List<RetrainRequest> all() {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<RetrainRequest> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f :
          files
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .sorted()
              .collect(Collectors.toList())) {
        out.add(read(f, Files.readAllBytes(f)));
      }
    } catch (IOException e) {
      throw new StoreException(dir, "cannot list retrain requests: " + e.getMessage(), e);
    }
    out.sort(Comparator.comparing(RetrainRequest::submittedAt));
    return out;
  }

  private static ObjectNode toJson(RetrainRequest r) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("retrain_id", r.retrainId());
    node.put("target", r.target());
    node.put("probe_id", r.probeId());
    node.put("draft_id", r.draftId());
    node.put("draft_fingerprint", r.draftFingerprint());
    node.put("triggered_by_job", r.triggeredByJob());
    node.put("checkpoint_step", r.checkpointStep());
    node.put("result_file", r.resultFile().toString());
    ObjectNode handle = node.putObject("handle");
    handle.put("executor", r.handle().executor());
    handle.put("native_id", r.handle().nativeId());
    handle.put("run_dir", r.handle().runDir().toString());
    handle.put("submitted_at", r.handle().submittedAt().toString());
    if (r.handle().nativeStartTime().isPresent()) {
      handle.put("native_start_time", r.handle().nativeStartTime().get().toString());
    } else {
      handle.putNull("native_start_time");
    }
    return node;
  }

  private RetrainRequest read(Path f, byte[] content) {
    try {
      JsonNode node = json.readTree(content);
      Fields r = new Fields(node, "");
      Fields h = r.object("handle");
      return RetrainRequest.of(
          r.text("retrain_id"),
          r.text("target"),
          r.text("probe_id"),
          r.text("draft_id"),
          r.text("draft_fingerprint"),
          r.text("triggered_by_job"),
          r.longValue("checkpoint_step"),
          r.path("result_file"),
          JobHandle.of(
              h.text("executor"),
              h.text("native_id"),
              h.path("run_dir"),
              h.instant("submitted_at"),
              h.optionalInstant("native_start_time")));
    } catch (IOException | RuntimeException e) {
      throw new StoreException(f, "is not a valid retrain request: " + e.getMessage(), e);
    }
  }
}
