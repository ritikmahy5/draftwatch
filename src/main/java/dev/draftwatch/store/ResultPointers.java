package dev.draftwatch.store;

/**
 * JSON Pointers (RFC 6901) to the values of a stored result file, as {@link JsonCodec} writes it:
 * {@code {"provenance": {...}, "report": {...}}}. The report links each number it shows to its
 * pointer; {@code ResultPointersTest} checks each against a written file.
 */
public final class ResultPointers {
  public static final String TARGET = "/provenance/target";
  public static final String CHECKPOINT_STEP = "/provenance/checkpoint_step";
  public static final String PROBE_ID = "/provenance/probe_id";
  public static final String PROBE_HASH = "/provenance/probe_hash";
  public static final String HARNESS_VERSION = "/provenance/harness_version";
  public static final String BACKEND = "/provenance/backend";
  public static final String JOB_ID = "/provenance/job_id";
  public static final String ATTEMPT = "/provenance/attempt";
  public static final String END_TIME = "/provenance/end_time";
  public static final String DRAFT_STRUCTURE = "/report/draft_structure";
  public static final String NUM_PROMPTS = "/report/num_prompts";
  public static final String HARDWARE_GPU = "/report/hardware/gpu";
  public static final String HARDWARE_COUNT = "/report/hardware/count";
  public static final String ALPHA_MEAN = "/report/aggregate/alpha_mean";
  public static final String ALPHA_STD = "/report/aggregate/alpha_std";
  public static final String TAU_MEAN = "/report/aggregate/tau_mean";
  public static final String TAU_STD = "/report/aggregate/tau_std";

  private ResultPointers() {}

  /** The seed number of seed entry {@code i} (0-based): {@code /report/seeds/<i>/seed}. */
  public static String seed(int i) {
    return "/report/seeds/" + i + "/seed";
  }

  /** {@code alpha_by_position} entry {@code k} (0-based) of seed entry {@code i}. */
  public static String alphaByPosition(int i, int k) {
    return "/report/seeds/" + i + "/alpha_by_position/" + k;
  }

  /** The position number of {@code position_counts} entry {@code k} of seed entry {@code i}. */
  public static String position(int i, int k) {
    return "/report/seeds/" + i + "/position_counts/" + k + "/position";
  }

  public static String positionCountsExact(int i) {
    return "/report/seeds/" + i + "/position_counts_exact";
  }
}
