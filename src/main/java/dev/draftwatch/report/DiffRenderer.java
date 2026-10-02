package dev.draftwatch.report;

import dev.draftwatch.domain.Provenance;
import java.util.ArrayList;
import java.util.List;

/** Renders a {@link MeasurementDiff} as aligned text for the terminal (DECISIONS.md D71). */
public final class DiffRenderer {
  private DiffRenderer() {}

  public static String render(MeasurementDiff d) {
    StringBuilder out = new StringBuilder();
    side(out, "A", d.a());
    side(out, "B", d.b());
    out.append('\n');
    table(out, "metric", d.metrics());
    out.append('\n');
    if (d.differences().isEmpty()) {
      out.append("provenance: no field differs\n");
    } else {
      out.append("provenance fields that differ:\n");
      for (MeasurementDiff.Line l : d.differences()) {
        // Stacked, not in columns: paths and fingerprints are too long to align.
        out.append("  ").append(l.field()).append('\n');
        out.append("    A  ").append(l.a()).append('\n');
        out.append("    B  ").append(l.b()).append('\n');
      }
    }
    out.append('\n');
    out.append(
            d.incomparable()
                .map(f -> "comparable: no, " + f + " differs (MEASUREMENT_CONTRACT.md,"
                    + " \"Comparability\")")
                .orElse("comparable: yes"))
        .append('\n');
    return out.toString();
  }

  private static void side(StringBuilder out, String label, MeasurementDiff.Side s) {
    Provenance p = s.measurement().provenance();
    out.append(label).append(": ").append(p.checkpoint().path()).append(", step ")
        .append(p.checkpoint().step()).append(", probe ").append(p.probeId()).append(", job ")
        .append(p.jobId()).append('\n');
    out.append("   ").append(s.file()).append('\n');
    if (!s.otherJobs().isEmpty()) {
      out.append("   (the latest of ").append(s.otherJobs().size() + 1)
          .append(" results; also stored: ").append(String.join(", ", s.otherJobs()))
          .append(")\n");
    }
  }

  private static void table(StringBuilder out, String heading, List<MeasurementDiff.Line> lines) {
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {heading, "A", "B"});
    for (MeasurementDiff.Line l : lines) {
      rows.add(new String[] {l.field(), l.a(), l.b()});
    }
    int w0 = 0;
    int w1 = 0;
    for (String[] r : rows) {
      w0 = Math.max(w0, r[0].length());
      w1 = Math.max(w1, r[1].length());
    }
    for (String[] r : rows) {
      out.append("  ").append(pad(r[0], w0)).append("  ").append(pad(r[1], w1)).append("  ")
          .append(r[2]).append('\n');
    }
  }

  private static String pad(String s, int width) {
    StringBuilder b = new StringBuilder(s);
    while (b.length() < width) {
      b.append(' ');
    }
    return b.toString();
  }
}
