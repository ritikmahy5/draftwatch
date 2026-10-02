package dev.draftwatch.store;

import dev.draftwatch.domain.Measurement;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Append-only measurement results (Repository; ARCHITECTURE.md, "Key interfaces"). Results are
 * keyed by job id, so a re-measurement never overwrites an earlier one.
 */
public interface ResultRepository {
  /**
   * Stores {@code m}. Storing the identical measurement again is a no-op, which makes a retried
   * write after a crash safe (DECISIONS.md D37).
   *
   * @throws StoreException if a different measurement is already stored under its job id
   */
  void append(Measurement m);

  /** Results of {@code target} under {@code probeHash}, in step order (then end time, job id). */
  List<Measurement> history(String target, String probeHash);

  /** Every result for one checkpoint and probe, oldest first by end time. */
  List<Measurement> find(String fingerprint, String probeHash);

  Optional<Measurement> latest(String fingerprint, String probeHash);

  /** Every result, by target name, then in {@link #history} order (DECISIONS.md D72). */
  List<Measurement> all();

  /** Where {@code m} is stored, so every reported number can be traced to its file. */
  Path locate(Measurement m);
}
