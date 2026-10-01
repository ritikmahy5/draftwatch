package dev.draftwatch.fingerprint;

import java.nio.file.Path;

/**
 * Fingerprints weight files by hashing every byte ({@code fingerprint: full}). Slower than
 * {@link SampledBlockFingerprinter} on multi-GB checkpoints, but sees every change.
 */
public final class FullFileFingerprinter extends WeightFileFingerprinter {
  public FullFileFingerprinter() {
    super(FingerprintMethod.FULL);
  }

  @Override
  protected byte[] contentDigest(Path file, long size) {
    return digestOf(file, size, ranges -> ranges.digest(0, size));
  }
}
