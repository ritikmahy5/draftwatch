package dev.draftwatch.testing;

import dev.draftwatch.domain.Measurement;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.store.StoreException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** A {@link ResultRepository} in memory with the same append-only rules, for tests. */
public final class InMemoryResultRepository implements ResultRepository {
  private final Map<String, Measurement> byJob = new LinkedHashMap<>();

  @Override
  public void append(Measurement m) {
    Measurement existing = byJob.get(m.jobId());
    if (existing != null && !existing.equals(m)) {
      throw new StoreException(locate(m), "a different result is already stored");
    }
    byJob.put(m.jobId(), m);
  }

  @Override
  public List<Measurement> history(String target, String probeHash) {
    return byJob.values().stream()
        .filter(m -> m.provenance().targetName().equals(target))
        .filter(m -> m.probeHash().equals(probeHash))
        .sorted(
            Comparator.comparingLong((Measurement m) -> m.provenance().checkpoint().step())
                .thenComparing(m -> m.provenance().endTime()))
        .collect(Collectors.toList());
  }

  @Override
  public List<Measurement> find(String fingerprint, String probeHash) {
    return byJob.values().stream()
        .filter(m -> m.fingerprint().equals(fingerprint) && m.probeHash().equals(probeHash))
        .sorted(Comparator.comparing((Measurement m) -> m.provenance().endTime()))
        .collect(Collectors.toList());
  }

  @Override
  public Optional<Measurement> latest(String fingerprint, String probeHash) {
    List<Measurement> all = find(fingerprint, probeHash);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
  }

  @Override
  public Path locate(Measurement m) {
    return Paths.get("memory", m.jobId() + ".json");
  }

  public List<Measurement> all() {
    return new ArrayList<>(byJob.values());
  }
}
