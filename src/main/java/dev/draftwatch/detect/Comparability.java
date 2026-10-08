package dev.draftwatch.detect;

import dev.draftwatch.domain.Measurement;
import java.util.Optional;

/**
 * The Comparability guard (MEASUREMENT_CONTRACT.md, "Comparability"): two measurements may be
 * compared only if their probe hash, harness version, backend, and draft structure all match.
 * {@code hardware} and {@code wall_clock_seconds} are never compared.
 */
public final class Comparability {
  private Comparability() {}

  /**
   * The first field, in the contract's order, on which {@code a} and {@code b} differ: one of
   * {@code probe_hash}, {@code harness_version}, {@code backend}, {@code draft_structure},
   * {@code hardware} (the GPU model and count).
   * Empty if they are comparable.
   */
  public static Optional<String> mismatch(Measurement a, Measurement b) {
    if (!a.probeHash().equals(b.probeHash())) {
      return Optional.of("probe_hash");
    }
    if (!a.report().harnessVersion().equals(b.report().harnessVersion())) {
      return Optional.of("harness_version");
    }
    if (!a.report().backend().equals(b.report().backend())) {
      return Optional.of("backend");
    }
    if (a.report().draftStructure() != b.report().draftStructure()) {
      return Optional.of("draft_structure");
    }
    if (!a.report().hardware().equals(b.report().hardware())) {
      return Optional.of("hardware");
    }
    return Optional.empty();
  }

  public static boolean comparable(Measurement a, Measurement b) {
    return mismatch(a, b).isEmpty();
  }
}
