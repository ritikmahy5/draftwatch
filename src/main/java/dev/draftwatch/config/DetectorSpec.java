package dev.draftwatch.config;

import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.WireNamed;

/**
 * One configured regression detector (SPEC.md F5) with validated parameters. Each detector kind
 * has its own spec class, so a detector receives typed parameters rather than a map.
 */
public interface DetectorSpec {
  /** The detectors of SPEC.md F5. */
  enum Kind implements WireNamed {
    PAIRED_BOOTSTRAP("paired_bootstrap"),
    ABSOLUTE_DROP("absolute_drop"),
    NOISE_FLOOR("noise_floor"),
    TREND("trend");

    private final String wireName;

    Kind(String wireName) {
      this.wireName = wireName;
    }

    @Override
    public String wireName() {
      return wireName;
    }
  }

  Kind kind();

  Metric metric();

  /** Config-like text with every parameter, defaults included, for {@code validate}. */
  String describe();
}
