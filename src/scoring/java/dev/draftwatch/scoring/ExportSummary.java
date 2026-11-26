package dev.draftwatch.scoring;

import java.nio.file.Path;
import java.util.List;

/** What an export wrote, and which Finder duplicates it verified and skipped. */
public final class ExportSummary {
  private final List<Path> written;
  private final List<Path> skippedDuplicates;

  ExportSummary(List<Path> written, List<Path> skippedDuplicates) {
    this.written = List.copyOf(written);
    this.skippedDuplicates = List.copyOf(skippedDuplicates);
  }

  /** The files written, under the destination, in the order written. */
  public List<Path> written() {
    return written;
  }

  /** Byte-identical {@code <name> 2.<ext>} copies in the source that were not exported. */
  public List<Path> skippedDuplicates() {
    return skippedDuplicates;
  }
}
