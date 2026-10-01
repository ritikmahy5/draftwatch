package dev.draftwatch.domain;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A fixed measurement recipe: prompt set, decoding settings, draft, estimator, and seeds.
 *
 * <p>This is the recipe as configured. Its identity for comparisons is the probe hash, which
 * also covers the draft's fingerprint and the prompt file's contents; see {@link ResolvedProbe}.
 */
public final class Probe {
  private final String id;
  private final Draft draft;
  private final Path promptsPath;
  private final Decoding decoding;
  private final Estimator estimator;
  private final List<Integer> seeds;

  private Probe(
      String id,
      Draft draft,
      Path promptsPath,
      Decoding decoding,
      Estimator estimator,
      List<Integer> seeds) {
    this.id = id;
    this.draft = draft;
    this.promptsPath = promptsPath;
    this.decoding = decoding;
    this.estimator = estimator;
    this.seeds = seeds;
  }

  /**
   * Creates a probe.
   *
   * @param seeds decoding seeds in the order they are passed to the harness; non-empty,
   *     distinct, and non-negative
   */
  public static Probe of(
      String id,
      Draft draft,
      Path promptsPath,
      Decoding decoding,
      Estimator estimator,
      List<Integer> seeds) {
    List<Integer> seedsCopy = Require.nonEmptyCopy(seeds, "seeds");
    Set<Integer> seen = new HashSet<>();
    for (int seed : seedsCopy) {
      Require.nonNegative(seed, "seed");
      if (!seen.add(seed)) {
        throw new IllegalArgumentException("seeds must be distinct; " + seed + " repeats");
      }
    }
    return new Probe(
        Names.require(id, "probe id"),
        Require.nonNull(draft, "draft"),
        Require.nonNull(promptsPath, "prompts path"),
        Require.nonNull(decoding, "decoding"),
        Require.nonNull(estimator, "estimator"),
        seedsCopy);
  }

  public String id() {
    return id;
  }

  public Draft draft() {
    return draft;
  }

  public Path promptsPath() {
    return promptsPath;
  }

  public Decoding decoding() {
    return decoding;
  }

  public Estimator estimator() {
    return estimator;
  }

  /** Unmodifiable, in configured order. */
  public List<Integer> seeds() {
    return seeds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Probe)) {
      return false;
    }
    Probe that = (Probe) o;
    return id.equals(that.id)
        && draft.equals(that.draft)
        && promptsPath.equals(that.promptsPath)
        && decoding.equals(that.decoding)
        && estimator == that.estimator
        && seeds.equals(that.seeds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, draft, promptsPath, decoding, estimator, seeds);
  }

  @Override
  public String toString() {
    return "Probe{" + id + "}";
  }
}
