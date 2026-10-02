package dev.draftwatch.events;

import dev.draftwatch.domain.Measurement;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** A validated measurement was appended to the result store. */
public final class MeasurementStored implements Event {
  private final Instant at;
  private final Measurement measurement;
  private final Path resultFile;

  public MeasurementStored(Instant at, Measurement measurement, Path resultFile) {
    this.at = Objects.requireNonNull(at, "at");
    this.measurement = Objects.requireNonNull(measurement, "measurement");
    this.resultFile = Objects.requireNonNull(resultFile, "resultFile");
  }

  @Override
  public Instant at() {
    return at;
  }

  public Measurement measurement() {
    return measurement;
  }

  /** Where the result is stored. */
  public Path resultFile() {
    return resultFile;
  }

  @Override
  public String toString() {
    return "MeasurementStored{job " + measurement.jobId() + "}";
  }
}
