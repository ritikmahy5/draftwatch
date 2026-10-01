package dev.draftwatch.fingerprint;

import java.nio.file.Path;

/**
 * Computes a content fingerprint of the weight files in a checkpoint or draft directory.
 *
 * <p>Strategy: the trade-off between speed and completeness is chosen in config
 * ({@code fingerprint: sampled | full}).
 */
public interface Fingerprinter {
  /**
   * Fingerprints the weight files under {@code dir}.
   *
   * @return {@code <method>-<64 lowercase hex>}; fingerprints from different methods never
   *     compare equal
   * @throws FingerprintException if {@code dir} is not a readable directory, holds no weight
   *     files, or a file changes size while it is being read
   */
  String fingerprint(Path dir);
}
