package dev.draftwatch.testing;

import dev.draftwatch.store.DetectionLog;
import dev.draftwatch.store.DetectionRecord;
import java.util.ArrayList;
import java.util.List;

/** A {@link DetectionLog} in memory, for tests. */
public final class InMemoryDetectionLog implements DetectionLog {
  private final List<DetectionRecord> records = new ArrayList<>();

  @Override
  public void append(DetectionRecord record) {
    records.add(record);
  }

  @Override
  public List<DetectionRecord> all() {
    return List.copyOf(records);
  }
}
