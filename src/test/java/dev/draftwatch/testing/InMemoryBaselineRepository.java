package dev.draftwatch.testing;

import dev.draftwatch.domain.Baseline;
import dev.draftwatch.store.BaselineRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A {@link BaselineRepository} in memory, for tests. */
public final class InMemoryBaselineRepository implements BaselineRepository {
  private final Map<String, Baseline> baselines = new HashMap<>();

  @Override
  public Optional<Baseline> get(String target) {
    return Optional.ofNullable(baselines.get(target));
  }

  @Override
  public void set(Baseline baseline) {
    baselines.put(baseline.targetName(), baseline);
  }
}
