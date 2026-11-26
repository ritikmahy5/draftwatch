package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.stats.MetricCalculator;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Entry point of the scoring tool: {@code export} copies the run archive, {@code score} scores it. */
public final class ScoreMain {
  /** What the cluster prefix becomes in committed files (the existing fixtures' placeholder). */
  static final String PLACEHOLDER = "/scratch/user";

  static final String COMMIT = "draftwatch.commit";
  static final String DIRTY = "draftwatch.dirty";

  static final String USAGE =
      "usage: score export --state <dir> --out <dir> --cluster-prefix <prefix> [--extra <file>]...\n"
          + "             [--extra-dir <name>=<dir>]...\n"
          + "       score score --state <dir> --out <dir> [--sacct <file>] [--pilot <dir>]\n"
          + "             [--sizes 25,50,100,200] [--subsets 500] [--allow-dirty]";

  private ScoreMain() {}

  public static void main(String[] args) {
    int code = run(Arrays.asList(args), System.out, System.err);
    System.out.flush();
    System.exit(code);
  }

  /** Runs one subcommand with the build information Gradle passes as system properties. */
  static int run(List<String> args, PrintStream out, PrintStream err) {
    Map<String, String> build = new HashMap<>();
    for (String key : List.of(COMMIT, DIRTY)) {
      String v = System.getProperty(key);
      if (v != null) {
        build.put(key, v);
      }
    }
    return run(args, out, err, build);
  }

  /** Runs one subcommand and returns the exit code: 0 on success, 1 on failure, 2 on misuse. */
  static int run(List<String> args, PrintStream out, PrintStream err, Map<String, String> build) {
    if (args.isEmpty()) {
      return usage(err, "no subcommand");
    }
    switch (args.get(0)) {
      case "export":
        return export(args.subList(1, args.size()), out, err);
      case "score":
        return score(args.subList(1, args.size()), out, err, build);
      default:
        return usage(err, "unknown subcommand: " + args.get(0));
    }
  }

  private static int export(List<String> args, PrintStream out, PrintStream err) {
    Flags flags;
    String state;
    String dest;
    String prefix;
    try {
      flags =
          Flags.parse(
              args, Set.of("--state", "--out", "--cluster-prefix", "--extra", "--extra-dir"),
              Set.of());
      state = flags.required("--state");
      dest = flags.required("--out");
      prefix = flags.required("--cluster-prefix");
    } catch (IllegalArgumentException e) {
      return usage(err, e.getMessage());
    }
    List<Path> extras = new ArrayList<>();
    for (String extra : flags.all("--extra")) {
      extras.add(Paths.get(extra));
    }
    Map<String, Path> extraDirs = new HashMap<>();
    for (String spec : flags.all("--extra-dir")) {
      int eq = spec.indexOf('=');
      if (eq < 1 || eq == spec.length() - 1 || spec.substring(0, eq).contains("/")) {
        return usage(err, "--extra-dir must be <name>=<dir>, was " + spec);
      }
      extraDirs.put(spec.substring(0, eq), Paths.get(spec.substring(eq + 1)));
    }
    try {
      ExportSummary summary =
          new ArchiveExporter(prefix, PLACEHOLDER, new ObjectMapper())
              .export(Paths.get(state), Paths.get(dest), extras, extraDirs);
      out.println("wrote " + summary.written().size() + " files to " + dest + "; skipped "
          + summary.skippedDuplicates().size() + " identical Finder duplicates");
      return 0;
    } catch (ExportException | IllegalArgumentException e) {
      err.println("export failed: " + e.getMessage());
      return 1;
    }
  }

  private static int score(
      List<String> args, PrintStream out, PrintStream err, Map<String, String> build) {
    Flags flags;
    List<Integer> sizes = ScoreOptions.SIZES;
    int subsets = ScoreOptions.SUBSETS_PER_SIZE;
    try {
      flags =
          Flags.parse(
              args,
              Set.of("--state", "--out", "--sacct", "--pilot", "--sizes", "--subsets"),
              Set.of("--allow-dirty"));
      flags.required("--state");
      flags.required("--out");
      if (flags.optional("--sizes").isPresent()) {
        sizes = sizes(flags.optional("--sizes").get());
      }
      if (flags.optional("--subsets").isPresent()) {
        subsets = number("--subsets", flags.optional("--subsets").get());
      }
    } catch (IllegalArgumentException e) {
      return usage(err, e.getMessage());
    }
    boolean allowDirty = flags.present("--allow-dirty");
    String commit = build.get(COMMIT);
    boolean dirty = "true".equals(build.get(DIRTY));
    if (commit == null && !allowDirty) {
      err.println("score failed: no tool commit; run it with ./gradlew score, which records it");
      return 1;
    }
    if (dirty && !allowDirty) {
      err.println("score failed: the working tree has uncommitted changes (to tracked files,"
          + " or untracked files under src/ or the build files); commit them first, so the"
          + " manifest's tool commit is the code that ran");
      return 1;
    }
    String recorded = commit == null ? "unknown" : commit + (dirty ? "-dirty" : "");
    Path state = Paths.get(flags.required("--state"));
    Path dest = Paths.get(flags.required("--out"));
    long start = System.nanoTime();
    try {
      ScoreOptions options =
          ScoreOptions.defaults(recorded, Paths.get("").toAbsolutePath())
              .withSizes(sizes)
              .withSubsetsPerSize(subsets);
      if (flags.optional("--sacct").isPresent()) {
        options = options.withSacct(Paths.get(flags.optional("--sacct").get()));
      }
      if (flags.optional("--pilot").isPresent()) {
        options = options.withPilotDir(Paths.get(flags.optional("--pilot").get()));
      }
      Scorer.Summary s =
          new Scorer(new MetricCalculator(), new ObjectMapper()).score(state, dest, options);
      out.println("cross-check: " + s.compared() + " measurements match detections.log exactly"
          + " for B0 and A; " + s.trendMismatches() + " T verdicts differ");
      out.println("wrote " + dest + " in " + (System.nanoTime() - start) / 1_000_000_000L + " s");
      return 0;
    } catch (DataException | ExportException | IllegalStateException | IllegalArgumentException e) {
      err.println("score failed: " + e.getMessage());
      return 1;
    }
  }

  private static List<Integer> sizes(String text) {
    List<Integer> out = new ArrayList<>();
    for (String part : text.split(",", -1)) {
      out.add(number("--sizes", part));
    }
    return out;
  }

  private static int number(String flag, String text) {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(flag + ": not a number: " + text);
    }
  }

  static int usage(PrintStream err, String problem) {
    err.println(problem);
    err.println(USAGE);
    return 2;
  }
}
