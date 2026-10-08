package dev.draftwatch.report;

import dev.draftwatch.report.ReportModel.Outcome;
import dev.draftwatch.report.ReportModel.PositionValue;
import dev.draftwatch.report.ReportModel.Positional;
import dev.draftwatch.report.ReportModel.Row;
import dev.draftwatch.report.ReportModel.SeedPositions;
import dev.draftwatch.report.ReportModel.Series;
import dev.draftwatch.report.ReportModel.TargetSection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Renders a {@link ReportModel} as one static HTML page: inline CSS and SVG, no scripts, no
 * external assets, well-formed XML. Every value from a result file is a link
 * to its file and JSON Pointer, and any other text that would contain a digit is refused while
 * rendering, so the page cannot show an untraced number.
 */
public final class HtmlReportRenderer {
  private static final int CHART_WIDTH = 560;
  private static final int CHART_HEIGHT = 200;
  private static final int LEFT = 150;
  private static final int RIGHT = 20;
  private static final int TOP = 16;
  private static final int BOTTOM = 40;
  private static final int PAD = 10;

  private static final String CSS =
      ":root{--bg:#fbfbfa;--fg:#1d2125;--muted:#5d6670;--line:#d9dde1;--card:#ffffff;"
          + "--alpha:#2f6fb5;--tau:#14806b;--s0:#6a4fb8;--s1:#c08a2a;--s2:#3f8f8a;"
          + "--regression:#c62d2d;--error:#b86e00;}"
          + "@media (prefers-color-scheme: dark){:root{--bg:#15181b;--fg:#e6e9ec;"
          + "--muted:#9aa4ae;--line:#323940;--card:#1d2125;--alpha:#79aef0;--tau:#4fc4a7;"
          + "--s0:#b39cf0;--s1:#f0c36a;--s2:#7fd1c9;--regression:#ff7272;--error:#f0a83c;}}"
          + "*{box-sizing:border-box}"
          + "body{margin:0;background:var(--bg);color:var(--fg);"
          + "font:15px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif}"
          + "main{max-width:1180px;margin:0 auto;padding:24px 16px 48px}"
          + "h1{font-size:26px;margin:0 0 4px}h2{font-size:21px;margin:36px 0 8px}"
          + "h3{font-size:17px;margin:0 0 6px}h4{font-size:15px;margin:20px 0 6px}"
          + "p{margin:4px 0}.muted{color:var(--muted)}"
          + "a{color:inherit;text-decoration:underline;text-decoration-color:var(--line);"
          + "text-underline-offset:2px}a:hover{text-decoration-color:currentColor}"
          + "code,.num{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}"
          + ".series{background:var(--card);border:1px solid var(--line);border-radius:10px;"
          + "padding:16px;margin:12px 0}"
          + ".key{color:var(--muted);font-size:13px;overflow-wrap:anywhere}"
          + ".charts{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0}"
          + ".charts figure{margin:0;flex:1 1 360px;min-width:0;max-width:600px}"
          + "figcaption{font-size:13px;color:var(--muted)}"
          + "svg{width:100%;height:auto;display:block}"
          + "svg .frame{fill:none;stroke:var(--line)}"
          + "svg .axis{font:11px ui-monospace,Menlo,monospace;fill:var(--muted)}"
          + "svg .line{fill:none;stroke-width:2}"
          + "svg .alpha{stroke:var(--alpha);fill:var(--alpha)}"
          + "svg .tau{stroke:var(--tau);fill:var(--tau)}"
          + "svg .s0,.swatch.s0{fill:var(--s0);background:var(--s0)}"
          + "svg .s1,.swatch.s1{fill:var(--s1);background:var(--s1)}"
          + "svg .s2,.swatch.s2{fill:var(--s2);background:var(--s2)}"
          + ".swatch{display:inline-block;width:10px;height:10px;border-radius:2px;"
          + "margin-right:6px;vertical-align:baseline}"
          + "svg .mk-regression{fill:none;stroke:var(--regression);stroke-width:2.5}"
          + "svg .mk-error{fill:none;stroke:var(--error);stroke-width:2.5}"
          + ".table-wrap{overflow-x:auto}"
          + "table{border-collapse:collapse;font-size:13px;margin:8px 0}"
          + "th,td{text-align:left;padding:4px 10px;border-bottom:1px solid var(--line);"
          + "vertical-align:top}"
          + "th,td.num,td.files,td code{white-space:nowrap}td.outcomes{min-width:220px}"
          + "th{color:var(--muted);font-weight:600}"
          + ".badge{display:inline-block;padding:0 6px;border-radius:6px;font-size:12px;"
          + "border:1px solid currentColor}"
          + ".regression{color:var(--regression)}.error{color:var(--error)}"
          + ".approximate{color:var(--error)}";

  private final Path reportDir;
  private final StringBuilder html = new StringBuilder();

  private HtmlReportRenderer(Path reportFile) {
    this.reportDir = reportFile.toAbsolutePath().normalize().getParent();
  }

  /**
   * The page for {@code model}, to be written at {@code reportFile}; links are relative to it.
   *
   * @throws IllegalStateException if any text that is not a traced value contains a digit
   */
  public static String render(ReportModel model, Path reportFile, Instant generatedAt) {
    HtmlReportRenderer r = new HtmlReportRenderer(Objects.requireNonNull(reportFile));
    r.page(model, Objects.requireNonNull(generatedAt));
    return r.html.toString();
  }

  // --- page --------------------------------------------------------------------------------

  private void page(ReportModel model, Instant generatedAt) {
    raw("<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n<head>\n"
        + "<meta charset=\"utf-8\" />\n"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
        + "<meta name=\"generator\" content=\"draftwatch report\" />\n");
    raw("<meta name=\"generated-at\" content=\"" + attr(generatedAt.toString()) + "\" />\n");
    raw("<title>draftwatch report</title>\n<style>" + CSS + "</style>\n</head>\n<body>\n<main>\n");
    raw("<h1>draftwatch report</h1>\n<p class=\"muted\">");
    text(
        "Draft acceptance measured on each target checkpoint. Every number links to the stored"
            + " result file it is read from, and to the value's place in that file.");
    raw("</p>\n<p class=\"muted\">");
    text(
        "alpha_mean: the mean over seeds of alpha, the fraction of proposed draft tokens that the"
            + " target accepted. tau_mean: the mean over seeds of tau, the tokens emitted per"
            + " target forward pass.");
    raw("</p>\n");
    if (model.targets().isEmpty()) {
      raw("<p>");
      text("No results are stored yet.");
      raw("</p>\n");
    }
    for (TargetSection t : model.targets()) {
      raw("<h2>");
      text("Target ");
      value(t.name());
      raw("</h2>\n");
      for (Series s : t.series()) {
        series(s, model.detectionsLog());
      }
    }
    raw("<p class=\"muted\">");
    text("Detection outcomes are recorded, with the numbers each detector computed, in ");
    link(model.detectionsLog(), "detections.log");
    text(".");
    raw("</p>\n</main>\n</body>\n</html>\n");
  }

  private void series(Series s, Path detectionsLog) {
    raw("<section class=\"series\">\n<h3>");
    text("Probe ");
    value(s.probeId());
    raw("</h3>\n<p class=\"key\">");
    text("probe hash ");
    raw("<code>");
    value(s.probeHash());
    raw("</code>");
    text(" · harness ");
    value(s.harnessVersion());
    text(" · backend ");
    value(s.backend());
    text(" · draft structure ");
    value(s.draftStructure());
    text(" · GPU ");
    value(s.gpu());
    text(" × ");
    value(s.gpuCount());
    raw("</p>\n<div class=\"charts\">\n");
    chart("alpha_mean by checkpoint step", "alpha", s.rows(), Row::alphaValue, Row::alphaMean,
        detectionsLog);
    chart("tau_mean by checkpoint step", "tau", s.rows(), Row::tauValue, Row::tauMean,
        detectionsLog);
    raw("</div>\n");
    table(s, detectionsLog);
    positional(s.latest());
    raw("</section>\n");
  }

  // --- the metric charts -------------------------------------------------------------------

  private void chart(
      String caption,
      String css,
      List<Row> rows,
      ToDoubleFunction<Row> value,
      Function<Row, Traced> traced,
      Path detectionsLog) {
    long minStep = rows.get(0).stepValue();
    long maxStep = rows.get(rows.size() - 1).stepValue();
    Row low = rows.get(0);
    Row high = rows.get(0);
    for (Row r : rows) {
      if (value.applyAsDouble(r) < value.applyAsDouble(low)) {
        low = r;
      }
      if (value.applyAsDouble(r) > value.applyAsDouble(high)) {
        high = r;
      }
    }
    double minV = value.applyAsDouble(low);
    double maxV = value.applyAsDouble(high);
    Function<Long, Double> x =
        step ->
            minStep == maxStep
                ? LEFT + (CHART_WIDTH - LEFT - RIGHT) / 2.0
                : LEFT + PAD
                    + (step - minStep) * (CHART_WIDTH - LEFT - RIGHT - 2.0 * PAD)
                        / (maxStep - minStep);
    ToDoubleFunction<Double> y =
        v ->
            minV == maxV
                ? TOP + (CHART_HEIGHT - TOP - BOTTOM) / 2.0
                : CHART_HEIGHT - BOTTOM - PAD
                    - (v - minV) * (CHART_HEIGHT - TOP - BOTTOM - 2.0 * PAD) / (maxV - minV);
    raw("<figure>\n");
    raw("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + CHART_WIDTH + " "
        + CHART_HEIGHT + "\" role=\"img\" aria-label=\"" + attr(caption) + "\">\n");
    raw("<rect class=\"frame\" x=\"" + LEFT + "\" y=\"" + TOP + "\" width=\""
        + (CHART_WIDTH - LEFT - RIGHT) + "\" height=\"" + (CHART_HEIGHT - TOP - BOTTOM)
        + "\" />\n");
    // The line runs through the latest result of each step; every result is a point.
    Map<Long, Row> latestPerStep = new LinkedHashMap<>();
    for (Row r : rows) {
      latestPerStep.put(r.stepValue(), r);
    }
    List<String> points = new ArrayList<>();
    for (Row r : latestPerStep.values()) {
      points.add(num(x.apply(r.stepValue())) + "," + num(y.applyAsDouble(value.applyAsDouble(r))));
    }
    if (points.size() > 1) {
      raw("<polyline class=\"line " + css + "\" style=\"fill:none\" points=\""
          + String.join(" ", points) + "\" />\n");
    }
    for (Row r : rows) {
      double cx = x.apply(r.stepValue());
      double cy = y.applyAsDouble(value.applyAsDouble(r));
      raw("<a href=\"" + attr(href(traced.apply(r))) + "\"><circle class=\"" + css + "\" cx=\""
          + num(cx) + "\" cy=\"" + num(cy) + "\" r=\"4\" /></a>\n");
      if (r.hasRegression()) {
        raw("<a href=\"" + attr(href(detectionsLog)) + "\"><circle class=\"mk-regression\" cx=\""
            + num(cx) + "\" cy=\"" + num(cy) + "\" r=\"9\" /></a>\n");
      }
      if (r.hasError()) {
        raw("<a href=\"" + attr(href(detectionsLog)) + "\"><path class=\"mk-error\" d=\"M"
            + num(cx - 6) + " " + num(cy - 14) + " L" + num(cx + 6) + " " + num(cy - 26) + " M"
            + num(cx - 6) + " " + num(cy - 26) + " L" + num(cx + 6) + " " + num(cy - 14)
            + "\" /></a>\n");
      }
    }
    // Axis labels are the extreme data values themselves, each linked.
    svgLabel(traced.apply(high), LEFT - 8, y.applyAsDouble(maxV) + 4, "end");
    if (minV != maxV) {
      svgLabel(traced.apply(low), LEFT - 8, y.applyAsDouble(minV) + 4, "end");
    }
    double axisY = CHART_HEIGHT - BOTTOM + 16;
    svgLabel(rows.get(0).step(), x.apply(minStep), axisY, minStep == maxStep ? "middle" : "start");
    if (minStep != maxStep) {
      svgLabel(rows.get(rows.size() - 1).step(), x.apply(maxStep), axisY, "end");
    }
    raw("<text class=\"axis\" x=\"" + (LEFT + (CHART_WIDTH - LEFT - RIGHT) / 2) + "\" y=\""
        + (CHART_HEIGHT - 6) + "\" text-anchor=\"middle\">");
    text("checkpoint step");
    raw("</text>\n</svg>\n<figcaption>");
    text(caption + ". Ringed points have a regression, crossed points an error.");
    raw("</figcaption>\n</figure>\n");
  }

  private void svgLabel(Traced t, double x, double y, String anchor) {
    raw("<a href=\"" + attr(href(t)) + "\"><text class=\"axis\" x=\"" + num(x) + "\" y=\""
        + num(y) + "\" text-anchor=\"" + anchor + "\">" + esc(t.text()) + "</text></a>\n");
  }

  // --- the results table -------------------------------------------------------------------

  private void table(Series s, Path detectionsLog) {
    boolean std = s.hasStd();
    raw("<div class=\"table-wrap\"><table>\n<thead><tr>");
    List<String> headers = new ArrayList<>(List.of("step", "alpha_mean", "tau_mean"));
    if (std) {
      headers.addAll(List.of("alpha_std", "tau_std"));
    }
    headers.addAll(List.of("job", "finished", "detection outcomes", "files"));
    for (String h : headers) {
      raw("<th>");
      text(h);
      raw("</th>");
    }
    raw("</tr></thead>\n<tbody>\n");
    for (Row r : s.rows()) {
      raw("<tr><td class=\"num\">");
      value(r.step());
      raw("</td><td class=\"num\">");
      value(r.alphaMean());
      raw("</td><td class=\"num\">");
      value(r.tauMean());
      raw("</td>");
      if (std) {
        for (Optional<Traced> v : List.of(r.alphaStd(), r.tauStd())) {
          raw("<td class=\"num\">");
          if (v.isPresent()) {
            value(v.get());
          } else {
            text("none");
          }
          raw("</td>");
        }
      }
      raw("<td><code>");
      value(r.jobId());
      raw("</code></td><td class=\"num\">");
      value(r.endTime());
      raw("</td><td class=\"outcomes\">");
      outcomes(r.outcomes(), detectionsLog);
      raw("</td><td class=\"files\">");
      link(r.resultFile(), "result");
      text(" · ");
      link(r.rawReport(), "raw report");
      raw("</td></tr>\n");
    }
    raw("</tbody>\n</table></div>\n");
  }

  private void outcomes(List<Outcome> outcomes, Path detectionsLog) {
    if (outcomes.isEmpty()) {
      text("none recorded");
      return;
    }
    boolean first = true;
    for (Outcome o : outcomes) {
      if (!first) {
        raw("<br />");
      }
      first = false;
      String css = o.isRegression() ? "regression" : o.isError() ? "error" : "";
      String label =
          o.detector().map(d -> d + o.metric().map(m -> " on " + m).orElse("") + ": ").orElse("")
              + o.kind();
      raw("<a class=\"" + css + "\" href=\"" + attr(href(detectionsLog)) + "\">");
      text(label);
      raw("</a>");
    }
  }

  // --- positional acceptance ---------------------------------------------------------------

  private void positional(Positional p) {
    raw("<h4>");
    text("Positional acceptance of the latest checkpoint");
    raw("</h4>\n<p class=\"muted\">");
    text("Step ");
    value(p.row().step());
    text(", job ");
    raw("<code>");
    value(p.row().jobId());
    raw("</code>");
    text(
        ". For each position, the fraction of steps reaching it whose draft token there was"
            + " accepted. Bar height runs from none to all.");
    raw("</p>\n");
    List<SeedPositions> seeds = p.seeds();
    int positions = seeds.get(0).positions().size();
    int group = Math.max(1, seeds.size());
    double slot = (CHART_WIDTH - LEFT - RIGHT) / (double) positions;
    double barWidth = Math.min(28, (slot - 8) / group);
    double plotTop = TOP;
    double plotBottom = CHART_HEIGHT - BOTTOM;
    raw("<div class=\"charts\"><figure>\n");
    raw("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + CHART_WIDTH + " "
        + CHART_HEIGHT + "\" role=\"img\" aria-label=\"positional acceptance\">\n");
    raw("<rect class=\"frame\" x=\"" + LEFT + "\" y=\"" + TOP + "\" width=\""
        + (CHART_WIDTH - LEFT - RIGHT) + "\" height=\"" + (CHART_HEIGHT - TOP - BOTTOM)
        + "\" />\n");
    for (int k = 0; k < positions; k++) {
      double groupLeft = LEFT + k * slot + (slot - barWidth * group) / 2;
      for (int s = 0; s < seeds.size(); s++) {
        PositionValue v = seeds.get(s).positions().get(k);
        if (v.alpha().isPresent()) {
          double h = v.alphaValue().getAsDouble() * (plotBottom - plotTop);
          raw("<a href=\"" + attr(href(v.alpha().get())) + "\"><rect class=\"" + seedClass(s)
              + "\" x=\""
              + num(groupLeft + s * barWidth) + "\" y=\"" + num(plotBottom - h) + "\" width=\""
              + num(barWidth - 2) + "\" height=\"" + num(h) + "\" /></a>\n");
        }
      }
      svgLabel(
          seeds.get(0).positions().get(k).position(),
          LEFT + k * slot + slot / 2,
          plotBottom + 16,
          "middle");
    }
    raw("<text class=\"axis\" x=\"" + (LEFT + (CHART_WIDTH - LEFT - RIGHT) / 2) + "\" y=\""
        + (CHART_HEIGHT - 6) + "\" text-anchor=\"middle\">");
    text("position within a step");
    raw("</text>\n</svg>\n<figcaption>");
    text("One bar per seed, colored as in the table. An undefined position has no bar.");
    raw("</figcaption>\n</figure></div>\n");

    raw("<div class=\"table-wrap\"><table>\n<thead><tr><th>");
    text("position");
    raw("</th>");
    for (SeedPositions s : seeds) {
      raw("<th><span class=\"swatch " + seedClass(seeds.indexOf(s)) + "\"></span>");
      text("seed ");
      value(s.seed());
      if (!s.exact()) {
        text(" ");
        raw("<span class=\"badge approximate\">");
        text("approximate");
        raw("</span>");
      }
      raw("</th>");
    }
    raw("</tr></thead>\n<tbody>\n");
    for (int k = 0; k < positions; k++) {
      raw("<tr><td class=\"num\">");
      value(seeds.get(0).positions().get(k).position());
      raw("</td>");
      for (SeedPositions s : seeds) {
        PositionValue v = s.positions().get(k);
        raw("<td class=\"num\">");
        if (v.alpha().isPresent()) {
          value(v.alpha().get());
        } else {
          text("undefined");
        }
        raw("</td>");
      }
      raw("</tr>\n");
    }
    raw("</tbody>\n</table></div>\n");
    if (seeds.stream().anyMatch(s -> !s.exact())) {
      raw("<p class=\"muted\">");
      text(
          "Approximate: the backend's position counts did not cover every proposed token, so"
              + " these values are estimates (MEASUREMENT_CONTRACT, reference backend).");
      raw("</p>\n");
    }
  }

  // --- output primitives -------------------------------------------------------------------

  private void raw(String markup) {
    html.append(markup);
  }

  /** Escaped text that is not a traced value; it may not contain a digit. */
  private void text(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isDigit(s.charAt(i))) {
        throw new IllegalStateException(
            "the report may not show an untraced number: '" + s + "'");
      }
    }
    html.append(esc(s));
  }

  /** A traced value: a link to its file and pointer. */
  private void value(Traced t) {
    html.append("<a class=\"v\" href=\"").append(attr(href(t))).append("\">")
        .append(esc(t.text())).append("</a>");
  }

  private void link(Path file, String label) {
    html.append("<a href=\"").append(attr(href(file))).append("\">");
    text(label);
    html.append("</a>");
  }

  private String href(Traced t) {
    return href(t.file()) + "#" + encode(t.pointer(), true);
  }

  /** {@code target} relative to the report's directory, percent-encoded per segment. */
  private String href(Path target) {
    Path rel = reportDir.relativize(target.toAbsolutePath().normalize());
    List<String> segments = new ArrayList<>();
    for (Path segment : rel) {
      segments.add(encode(segment.toString(), false));
    }
    String path = String.join("/", segments);
    return path.isEmpty() || path.split("/", 2)[0].contains(":") ? "./" + path : path;
  }

  /** Percent-encodes everything but RFC 3986 unreserved characters (and '/' if asked). */
  static String encode(String s, boolean keepSlash) {
    StringBuilder out = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '.' || c == '_' || c == '~' || (keepSlash && c == '/')) {
        out.append(c);
      } else {
        out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
      }
    }
    return out.toString();
  }

  /** The CSS class of the seed at {@code index}: three colors, repeated. */
  private static String seedClass(int index) {
    return "s" + (index % 3);
  }

  private static String num(double v) {
    return String.format(Locale.ROOT, "%.1f", v);
  }

  static String esc(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&':
          out.append("&amp;");
          break;
        case '<':
          out.append("&lt;");
          break;
        case '>':
          out.append("&gt;");
          break;
        case '"':
          out.append("&quot;");
          break;
        case '\'':
          out.append("&#39;");
          break;
        default:
          out.append(c);
      }
    }
    return out.toString();
  }

  private static String attr(String s) {
    return esc(s);
  }
}
