package dev.draftwatch.scoring;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/** Entry point of the scoring tool: {@code export} copies the run archive, {@code score} scores it. */
public final class ScoreMain {
  static final String USAGE =
      "usage: score export --state <dir> --out <dir> --cluster-prefix <prefix>\n"
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
      case "score":
        return usage(err, args.get(0) + " is not implemented yet");
      default:
        return usage(err, "unknown subcommand: " + args.get(0));
    }
  }

  static int usage(PrintStream err, String problem) {
    err.println(problem);
    err.println(USAGE);
    return 2;
  }
}
