package dev.draftwatch.testing;

import dev.draftwatch.store.RetrainRequest;
import dev.draftwatch.store.RetrainRequestRepository;
import dev.draftwatch.store.StoreException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link RetrainRequestRepository} in memory with the same one-per-draft rule, for tests. */
public final class InMemoryRetrainRequestRepository implements RetrainRequestRepository {
  private final Map<String, RetrainRequest> byKey = new LinkedHashMap<>();

  private static String key(String target, String draftId, String draftFingerprint) {
    return target + "__" + draftId + "__" + draftFingerprint;
  }

  @Override
  public Optional<RetrainRequest> find(String target, String draftId, String draftFingerprint) {
    return Optional.ofNullable(byKey.get(key(target, draftId, draftFingerprint)));
  }

  @Override
  public void record(RetrainRequest r) {
    String key = key(r.target(), r.draftId(), r.draftFingerprint());
    if (byKey.containsKey(key)) {
      throw new StoreException(Paths.get("memory", key), "already requested");
    }
    byKey.put(key, r);
  }

  @Override
  public List<RetrainRequest> all() {
    List<RetrainRequest> out = new ArrayList<>(byKey.values());
    out.sort(Comparator.comparing(RetrainRequest::submittedAt));
    return out;
  }
}
