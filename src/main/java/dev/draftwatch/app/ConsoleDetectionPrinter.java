package dev.draftwatch.app;

import dev.draftwatch.events.BaselinePinned;
import dev.draftwatch.events.DetectionEvent;
import java.io.PrintStream;
import java.util.Objects;

/** Prints every detection outcome and baseline change as it happens, for interactive commands. */
public final class ConsoleDetectionPrinter {
  private final PrintStream out;

  public ConsoleDetectionPrinter(PrintStream out) {
    this.out = Objects.requireNonNull(out, "out");
  }

  public void onDetection(DetectionEvent event) {
    out.println(
        "  detection " + event.kind() + " (job " + event.subject().jobId() + ", step "
            + event.subject().step() + ") " + event.explanation());
  }

  public void onBaselinePinned(BaselinePinned event) {
    out.println(
        "baseline for target " + event.baseline().targetName() + " pinned to step "
            + event.baseline().step() + " (" + event.baseline().path()
            + "), its first measured checkpoint; change it with 'draftwatch baseline "
            + event.baseline().targetName() + " <checkpoint>'");
  }
}
