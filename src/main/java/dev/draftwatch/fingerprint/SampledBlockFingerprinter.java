package dev.draftwatch.fingerprint;

import java.nio.file.Path;

/**
 * Fingerprints weight files from sampled blocks ({@code fingerprint: sampled}, the default;
 * DECISIONS.md D11 and D23).
 *
 * <p>A file of at most {@code BLOCK_COUNT × BLOCK_BYTES} bytes is hashed whole. A larger file
 * of size {@code S} contributes {@code BLOCK_COUNT} blocks of {@code BLOCK_BYTES} bytes, block
 * {@code i} starting at {@code floor(i × (S − BLOCK_BYTES) / (BLOCK_COUNT − 1))}: the first
 * block starts at 0 and the last one ends at the final byte. The blocks are digested in order.
 *
 * <p>A change confined to bytes outside every block is not detected; {@code fingerprint: full}
 * exists for that case. The file's size is part of the fingerprint, so truncation or growth is
 * always detected.
 */
public final class SampledBlockFingerprinter extends WeightFileFingerprinter {
  /** 1 MiB. */
  public static final int BLOCK_BYTES = 1 << 20;

  public static final int BLOCK_COUNT = 8;

  private final int blockBytes;
  private final int blockCount;

  public SampledBlockFingerprinter() {
    this(BLOCK_BYTES, BLOCK_COUNT);
  }

  /** Non-default block geometry, so tests can exercise sampling with small files. */
  SampledBlockFingerprinter(int blockBytes, int blockCount) {
    super(FingerprintMethod.SAMPLED);
    if (blockBytes <= 0 || blockCount < 2) {
      throw new IllegalArgumentException("need blockBytes > 0 and blockCount >= 2");
    }
    this.blockBytes = blockBytes;
    this.blockCount = blockCount;
  }

  @Override
  protected byte[] contentDigest(Path file, long size) {
    return digestOf(
        file,
        size,
        ranges -> {
          if (size <= (long) blockBytes * blockCount) {
            ranges.digest(0, size);
            return;
          }
          for (int i = 0; i < blockCount; i++) {
            ranges.digest(blockOffset(i, size), blockBytes);
          }
        });
  }

  /** Start of block {@code i} in a file of {@code size} bytes larger than all blocks together. */
  long blockOffset(int i, long size) {
    return i * (size - blockBytes) / (blockCount - 1);
  }
}
