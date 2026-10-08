package dev.draftwatch.store;

import java.util.List;
import java.util.Optional;

/** Submitted retrain jobs, one per target, draft id, and draft fingerprint (Repository). */
public interface RetrainRequestRepository {
  Optional<RetrainRequest> find(String target, String draftId, String draftFingerprint);

  /**
   * Records {@code r}.
   *
   * @throws StoreException if a request with the same target, draft id, and fingerprint exists
   */
  void record(RetrainRequest r);

  /** Every request, oldest submission first. */
  List<RetrainRequest> all();
}
