package dev.draftwatch.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Decoding settings of a probe, passed to the harness as {@code --decoding-json} and echoed
 * back in its report. Exactly the four keys of the report schema (DECISIONS.md D25).
 *
 * <p>{@code temperature} is kept as a decimal with trailing zeros stripped, so {@code 0} and
 * {@code 0.0} are the same value here, as they are in the probe hash.
 */
public final class Decoding {
  private final BigDecimal temperature;
  private final int maxNewTokens;
  private final int numSpeculativeTokens;
  private final String dtype;

  private Decoding(
      BigDecimal temperature, int maxNewTokens, int numSpeculativeTokens, String dtype) {
    this.temperature = temperature;
    this.maxNewTokens = maxNewTokens;
    this.numSpeculativeTokens = numSpeculativeTokens;
    this.dtype = dtype;
  }

  /**
   * Creates decoding settings.
   *
   * @throws IllegalArgumentException if {@code temperature} is negative, a token count is not
   *     positive, or {@code dtype} is blank
   */
  public static Decoding of(
      BigDecimal temperature, int maxNewTokens, int numSpeculativeTokens, String dtype) {
    Require.nonNull(temperature, "temperature");
    if (temperature.signum() < 0) {
      throw new IllegalArgumentException("temperature must be >= 0, was " + temperature);
    }
    return new Decoding(
        temperature.stripTrailingZeros(),
        Require.positive(maxNewTokens, "max_new_tokens"),
        Require.positive(numSpeculativeTokens, "num_speculative_tokens"),
        Require.nonBlank(dtype, "dtype"));
  }

  public BigDecimal temperature() {
    return temperature;
  }

  /** True when {@code temperature} is zero: decoding is greedy and seeds do not matter. */
  public boolean isGreedy() {
    return temperature.signum() == 0;
  }

  public int maxNewTokens() {
    return maxNewTokens;
  }

  /** Draft tokens proposed per verification step (the chain length). */
  public int numSpeculativeTokens() {
    return numSpeculativeTokens;
  }

  public String dtype() {
    return dtype;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Decoding)) {
      return false;
    }
    Decoding that = (Decoding) o;
    return temperature.equals(that.temperature)
        && maxNewTokens == that.maxNewTokens
        && numSpeculativeTokens == that.numSpeculativeTokens
        && dtype.equals(that.dtype);
  }

  @Override
  public int hashCode() {
    return Objects.hash(temperature, maxNewTokens, numSpeculativeTokens, dtype);
  }

  @Override
  public String toString() {
    return "Decoding{temperature="
        + temperature.toPlainString()
        + ", max_new_tokens="
        + maxNewTokens
        + ", num_speculative_tokens="
        + numSpeculativeTokens
        + ", dtype="
        + dtype
        + "}";
  }
}
