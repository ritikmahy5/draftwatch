package dev.draftwatch.domain;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One element of a report's {@code seeds} list: the metrics and counts for one decoding seed,
 * exactly as the harness reported them.
 *
 * <p>{@code alpha} is the empirical fraction of proposed draft tokens that the verifier
 * accepted; it is not the per-token acceptance probability of the speculative decoding
 * literature (MEASUREMENT_CONTRACT.md, "Metric definitions").
 *
 * <p>Built with a {@link Builder} because it has eleven fields.
 */
public final class SeedReport {
  private final int seed;
  private final double alpha;
  private final double tau;
  private final List<OptionalDouble> alphaByPosition;
  private final long totalSteps;
  private final long totalProposed;
  private final long totalAccepted;
  private final int excludedPrompts;
  private final boolean positionCountsExact;
  private final List<PromptCounts> perPrompt;
  private final List<PositionCount> positionCounts;

  private SeedReport(
      Builder b,
      List<OptionalDouble> alphaByPosition,
      List<PromptCounts> perPrompt,
      List<PositionCount> positionCounts) {
    this.seed = b.seed;
    this.alpha = b.alpha;
    this.tau = b.tau;
    this.alphaByPosition = alphaByPosition;
    this.totalSteps = b.totalSteps;
    this.totalProposed = b.totalProposed;
    this.totalAccepted = b.totalAccepted;
    this.excludedPrompts = b.excludedPrompts;
    this.positionCountsExact = b.positionCountsExact;
    this.perPrompt = perPrompt;
    this.positionCounts = positionCounts;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int seed() {
    return seed;
  }

  /** Acceptance rate: accepted / proposed draft tokens, under the report's estimator. */
  public double alpha() {
    return alpha;
  }

  /** Mean accepted length: mean over steps of (accepted + 1), under the report's estimator. */
  public double tau() {
    return tau;
  }

  /** Index k−1 holds position k; empty where no step was eligible at that position. */
  public List<OptionalDouble> alphaByPosition() {
    return alphaByPosition;
  }

  public long totalSteps() {
    return totalSteps;
  }

  public long totalProposed() {
    return totalProposed;
  }

  public long totalAccepted() {
    return totalAccepted;
  }

  /** Prompts left out of {@code simple_mean} alpha because they proposed nothing. */
  public int excludedPrompts() {
    return excludedPrompts;
  }

  /** False when some step proposed fewer than {@code num_speculative_tokens} positions. */
  public boolean positionCountsExact() {
    return positionCountsExact;
  }

  public List<PromptCounts> perPrompt() {
    return perPrompt;
  }

  public List<PositionCount> positionCounts() {
    return positionCounts;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SeedReport)) {
      return false;
    }
    SeedReport that = (SeedReport) o;
    return seed == that.seed
        && Double.compare(alpha, that.alpha) == 0
        && Double.compare(tau, that.tau) == 0
        && alphaByPosition.equals(that.alphaByPosition)
        && totalSteps == that.totalSteps
        && totalProposed == that.totalProposed
        && totalAccepted == that.totalAccepted
        && excludedPrompts == that.excludedPrompts
        && positionCountsExact == that.positionCountsExact
        && perPrompt.equals(that.perPrompt)
        && positionCounts.equals(that.positionCounts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        seed,
        alpha,
        tau,
        alphaByPosition,
        totalSteps,
        totalProposed,
        totalAccepted,
        excludedPrompts,
        positionCountsExact,
        perPrompt,
        positionCounts);
  }

  @Override
  public String toString() {
    return "SeedReport{seed=" + seed + ", alpha=" + alpha + ", tau=" + tau + "}";
  }

  /** Builder for {@link SeedReport}; {@link #build()} rejects null lists and elements. */
  public static final class Builder {
    private int seed;
    private double alpha;
    private double tau;
    private List<OptionalDouble> alphaByPosition;
    private long totalSteps;
    private long totalProposed;
    private long totalAccepted;
    private int excludedPrompts;
    private boolean positionCountsExact;
    private List<PromptCounts> perPrompt;
    private List<PositionCount> positionCounts;

    private Builder() {}

    public Builder seed(int seed) {
      this.seed = seed;
      return this;
    }

    public Builder alpha(double alpha) {
      this.alpha = alpha;
      return this;
    }

    public Builder tau(double tau) {
      this.tau = tau;
      return this;
    }

    public Builder alphaByPosition(List<OptionalDouble> alphaByPosition) {
      this.alphaByPosition = alphaByPosition;
      return this;
    }

    public Builder totalSteps(long totalSteps) {
      this.totalSteps = totalSteps;
      return this;
    }

    public Builder totalProposed(long totalProposed) {
      this.totalProposed = totalProposed;
      return this;
    }

    public Builder totalAccepted(long totalAccepted) {
      this.totalAccepted = totalAccepted;
      return this;
    }

    public Builder excludedPrompts(int excludedPrompts) {
      this.excludedPrompts = excludedPrompts;
      return this;
    }

    public Builder positionCountsExact(boolean positionCountsExact) {
      this.positionCountsExact = positionCountsExact;
      return this;
    }

    public Builder perPrompt(List<PromptCounts> perPrompt) {
      this.perPrompt = perPrompt;
      return this;
    }

    public Builder positionCounts(List<PositionCount> positionCounts) {
      this.positionCounts = positionCounts;
      return this;
    }

    public SeedReport build() {
      return new SeedReport(
          this,
          Require.copy(alphaByPosition, "alpha_by_position"),
          Require.copy(perPrompt, "per_prompt"),
          Require.copy(positionCounts, "position_counts"));
    }
  }
}
