package dev.draftwatch.scoring;

import dev.draftwatch.domain.Measurement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** One probe's measurements on one run, one per checkpoint, in step order. */
public final class Series {
  private final List<Measurement> measurements;

  Series(List<Measurement> measurements) {
    List<Measurement> sorted = new ArrayList<>(measurements);
    sorted.sort(Comparator.comparingLong(m -> m.provenance().checkpoint().step()));
    this.measurements = List.copyOf(sorted);
  }

  public List<Measurement> inStepOrder() {
    return measurements;
  }

  public Optional<Measurement> at(long step) {
    for (Measurement m : measurements) {
      if (m.provenance().checkpoint().step() == step) {
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }
}
