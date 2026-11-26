package dev.draftwatch.scoring;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How to score: the probe sizes and subsets per size (by default the spec's N ∈ {25, 50, 100,
 * 200} and 500), the optional sacct dump and pilot reports, the tool's commit, the root that
 * every reported path is relative to, and how many threads replay in parallel (the results do
 * not depend on it).
 */
public final class ScoreOptions {
  public static final List<Integer> SIZES = List.of(25, 50, 100, 200);
  public static final int SUBSETS_PER_SIZE = 500;

  private final List<Integer> sizes;
  private final int subsetsPerSize;
  private final Optional<Path> sacct;
  private final Optional<Path> pilotDir;
  private final String toolCommit;
  private final Path root;
  private final int threads;

  private ScoreOptions(
      List<Integer> sizes,
      int subsetsPerSize,
      Optional<Path> sacct,
      Optional<Path> pilotDir,
      String toolCommit,
      Path root,
      int threads) {
    this.sizes = List.copyOf(sizes);
    if (subsetsPerSize < 1) {
      throw new IllegalArgumentException("subsets per size must be >= 1, was " + subsetsPerSize);
    }
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be >= 1, was " + threads);
    }
    this.subsetsPerSize = subsetsPerSize;
    this.sacct = sacct;
    this.pilotDir = pilotDir;
    this.toolCommit = Objects.requireNonNull(toolCommit, "toolCommit");
    this.root = Objects.requireNonNull(root, "root");
    this.threads = threads;
  }

  public static ScoreOptions defaults(String toolCommit, Path root) {
    return new ScoreOptions(
        SIZES,
        SUBSETS_PER_SIZE,
        Optional.empty(),
        Optional.empty(),
        toolCommit,
        root,
        Runtime.getRuntime().availableProcessors());
  }

  public ScoreOptions withSizes(List<Integer> sizes) {
    return new ScoreOptions(sizes, subsetsPerSize, sacct, pilotDir, toolCommit, root, threads);
  }

  public ScoreOptions withSubsetsPerSize(int n) {
    return new ScoreOptions(sizes, n, sacct, pilotDir, toolCommit, root, threads);
  }

  public ScoreOptions withSacct(Path file) {
    return new ScoreOptions(
        sizes, subsetsPerSize, Optional.of(file), pilotDir, toolCommit, root, threads);
  }

  public ScoreOptions withPilotDir(Path dir) {
    return new ScoreOptions(
        sizes, subsetsPerSize, sacct, Optional.of(dir), toolCommit, root, threads);
  }

  public ScoreOptions withThreads(int n) {
    return new ScoreOptions(sizes, subsetsPerSize, sacct, pilotDir, toolCommit, root, n);
  }

  public List<Integer> sizes() {
    return sizes;
  }

  public int subsetsPerSize() {
    return subsetsPerSize;
  }

  public Optional<Path> sacct() {
    return sacct;
  }

  public Optional<Path> pilotDir() {
    return pilotDir;
  }

  public String toolCommit() {
    return toolCommit;
  }

  /** Every path in the output is relative to this directory, the repository root. */
  public Path root() {
    return root;
  }

  public int threads() {
    return threads;
  }
}
