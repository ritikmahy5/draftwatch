package dev.draftwatch.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which sbatch options a configured argument list may not set (DECISIONS.md D65). sbatch parses
 * options with {@code getopt_long} ({@code src/sbatch/opt.c}), which also accepts a unique
 * abbreviation of a long option ({@code --out=x} for {@code --output=x}). So an argument names an
 * option when it is the option, or a prefix of it that is not itself an sbatch option. A short
 * option matches with or without an attached value ({@code -o x}, {@code -ox}). Names and letters
 * are those of {@code src/common/slurm_opt.c}.
 */
public final class SbatchOptions {
  /** A long option name, without dashes, and its short letter if it has one. */
  private static final class Option {
    private final String name;
    private final Optional<Character> letter;

    private Option(String name, Optional<Character> letter) {
      this.name = name;
      this.letter = letter;
    }
  }

  /** Options draftwatch sets itself on every job it submits. */
  private static final List<Option> CONTROLLED =
      List.of(
          option("output", 'o'),
          option("error", 'e'),
          option("chdir", 'D'),
          option("job-name", 'J'),
          option("open-mode"),
          option("parsable"),
          option("requeue"),
          option("no-requeue"),
          option("wrap"),
          option("array", 'a'),
          option("wait", 'W'),
          option("test-only"));

  /** Options with their own {@code executor.slurm} keys. */
  private static final List<Option> OWN_KEYS =
      List.of(option("partition", 'p'), option("gres"), option("time", 't'));

  /** Options that request GPUs, which the schedule job must not (D63). */
  private static final List<Option> GPU =
      List.of(
          option("gres"),
          option("gres-flags"),
          option("gpus", 'G'),
          option("gpus-per-node"),
          option("gpus-per-socket"),
          option("gpus-per-task"),
          option("cpus-per-gpu"),
          option("mem-per-gpu"));

  /**
   * sbatch long options that are proper prefixes of a refused option ({@code --mem} of
   * {@code --mem-per-gpu}); {@code getopt_long} reads an exact name as that option, not as an
   * abbreviation.
   */
  private static final Set<String> EXACT_PREFIXES = Set.of("gpus", "gres", "mem");

  /** {@code SBATCH_*} variables that request GPUs; unset before submitting the schedule job. */
  public static final Set<String> GPU_VARIABLES =
      Set.of(
          "SBATCH_GRES",
          "SBATCH_GPUS",
          "SBATCH_GPUS_PER_NODE",
          "SBATCH_GPUS_PER_SOCKET",
          "SBATCH_GPUS_PER_TASK",
          "SBATCH_CPUS_PER_GPU",
          "SBATCH_MEM_PER_GPU");

  private SbatchOptions() {}

  private static Option option(String name) {
    return new Option(name, Optional.empty());
  }

  private static Option option(String name, char letter) {
    return new Option(name, Optional.of(letter));
  }

  /**
   * Why {@code arg} may not appear in {@code extra_sbatch_args}, if it may not.
   *
   * @return for example {@code "sets --output, which draftwatch sets itself"}
   */
  static Optional<String> refuseForMeasurement(String arg) {
    Optional<String> controlled = match(arg, CONTROLLED);
    if (controlled.isPresent()) {
      return Optional.of("sets --" + controlled.get() + ", which draftwatch sets itself");
    }
    return match(arg, OWN_KEYS)
        .map(name -> "sets --" + name + "; use executor.slurm." + name + " instead");
  }

  /** Why {@code arg} may not appear in {@code schedule_sbatch_args}, if it may not. */
  static Optional<String> refuseForSchedule(String arg) {
    Optional<String> controlled = match(arg, CONTROLLED);
    if (controlled.isPresent()) {
      return Optional.of("sets --" + controlled.get() + ", which draftwatch sets itself");
    }
    return match(arg, GPU)
        .map(name -> "sets --" + name + ", but the schedule job must not request a GPU");
  }

  /** The full name of the option in {@code options} that {@code arg} sets, if any. */
  private static Optional<String> match(String arg, List<Option> options) {
    if (arg.startsWith("--") && arg.length() > 2) {
      int equals = arg.indexOf('=');
      String given = equals < 0 ? arg.substring(2) : arg.substring(2, equals);
      for (Option o : options) {
        if (o.name.equals(given)) {
          return Optional.of(o.name);
        }
      }
      if (EXACT_PREFIXES.contains(given)) {
        return Optional.empty();
      }
      for (Option o : options) {
        if (o.name.startsWith(given)) {
          return Optional.of(o.name);
        }
      }
    } else if (arg.startsWith("-") && arg.length() > 1 && arg.charAt(1) != '-') {
      char letter = arg.charAt(1);
      for (Option o : options) {
        if (o.letter.isPresent() && o.letter.get() == letter) {
          return Optional.of(o.name);
        }
      }
    }
    return Optional.empty();
  }
}
