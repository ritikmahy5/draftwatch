package dev.draftwatch.store;

import java.util.List;

/** Every detection outcome, including OK and ERROR (ARCHITECTURE.md, "Persistence"). */
public interface DetectionLog {
  void append(DetectionRecord record);

  /** Every record, oldest first. */
  List<DetectionRecord> all();
}
