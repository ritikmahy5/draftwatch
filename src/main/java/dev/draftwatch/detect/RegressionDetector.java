package dev.draftwatch.detect;

import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Metric;
import java.util.List;
import java.util.Optional;

/**
 * Judges whether a new measurement is a regression (Strategy: the target's {@code detectors}
 * config selects and parameterizes the implementations).
 */
public interface RegressionDetector {
  /** The detector with its parameters, as written in verdicts and logs. */
  String describe();

  Metric metric();

  /**
   * Evaluates {@code current}.
   *
   * @param baseline the measurement of the baseline checkpoint to compare against; empty when
   *     {@code current} is itself the first measurement of the baseline checkpoint
   * @param history every stored result of the same target and probe hash, in step order,
   *     including {@code current}
   */
  DetectorVerdict evaluate(
      Measurement current, Optional<Measurement> baseline, List<Measurement> history);
}
