package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Entry point of the scoring tool: {@code export} copies the run archive, {@code score} scores it. */
public final class ScoreMain {
  /** What the cluster prefix becomes in committed files (the existing fixtures' placeholder). */
  static final String PLACEHOLDER = "/scratch/user";

  static final String USAGE =
      "usage: score export --state <dir> --out <dir> --cluster-prefix <prefix> [--extra <file>]...\n"
          + "       score score --state <dir> --out <dir> [--sacct <file>] [--pilot <dir>]";

  private ScoreMain() {}

  public static void main(String[] args) {
    int code = run(Arrays.asList(args), System.out, System.err);
    System.out.flush();
    System.exit(code);
  }

  /** Runs one subcommand and returns the exit code: 0 on success, 2 on a usage error. */
  static int run(List<String> args, PrintStream out, PrintStream err) {
    if (args.isEmpty()) {
      return usage(err, "no subcommand");
    }
    switch (args.get(0)) {
      case "export":
        return export(args.subList(1, args.size()), out, err);
      case "score":
        return usage(err, args.get(0) + " is not implemented yet");
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
      flags = Flags.parse(args, Set.of("--state", "--out", "--cluster-prefix", "--extra"), Set.of());
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
    try {
      ExportSummary summary =
          new ArchiveExporter(prefix, PLACEHOLDER, new ObjectMapper())
              .export(Paths.get(state), Paths.get(dest), extras);
      out.println("wrote " + summary.written().size() + " files to " + dest + "; skipped "
          + summary.skippedDuplicates().size() + " identical Finder duplicates");
      return 0;
    } catch (ExportException | IllegalArgumentException e) {
      err.println("export failed: " + e.getMessage());
      return 1;
    }
  }

  static int usage(PrintStream err, String problem) {
    err.println(problem);
    err.println(USAGE);
    return 2;
  }
}
