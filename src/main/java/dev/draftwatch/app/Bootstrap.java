package dev.draftwatch.app;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Factory that wires every object in the application; it is the only place where concrete
 * classes are chosen, so the rest of the code depends on interfaces.
 */
public final class Bootstrap {
  /** The command set from SPEC.md, section "CLI", in the order shown there. */
  static final List<CommandUsage> COMMANDS =
      List.of(
          CommandUsage.of("init", "", "create draftwatch.yaml template + state directory"),
          CommandUsage.of(
              "validate", "", "validate config, print resolved probes and trigger chains"),
          CommandUsage.of(
              "watch", "[--once]", "discover -> trigger -> submit -> poll -> detect"),
          CommandUsage.of(
              "submit", "<target> <ckpt>", "queue measurements for one checkpoint"),
          CommandUsage.of(
              "schedule", "[--interval 15m]", "Slurm only: self-resubmitting watch --once job"),
          CommandUsage.of("unschedule", "", "cancel the scheduled watch job"),
          CommandUsage.of("status", "", "non-terminal and recently failed jobs"),
          CommandUsage.of("history", "<target> --probe <id>", "results in step order"),
          CommandUsage.of(
              "diff",
              "<ckptA> <ckptB> --probe <id>",
              "compare two measurements with provenance"),
          CommandUsage.of("report", "[--out report.html]", "static HTML report"),
          CommandUsage.of(
              "baseline", "<target> [<ckpt>]", "set or show the baseline checkpoint"));

  private final PrintStream out;
  private final PrintStream err;

  public Bootstrap(PrintStream out, PrintStream err) {
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
  }

  public Cli cli() {
    return new Cli(COMMANDS, out, err);
  }
}
