package dev.draftwatch.app;

import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.report.HtmlReportRenderer;
import dev.draftwatch.report.ReportModel;
import dev.draftwatch.store.AtomicFiles;
import dev.draftwatch.store.FileDetectionLog;
import dev.draftwatch.store.StoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code draftwatch report [--out report.html]}: writes the static HTML report of every stored
 * result (SPEC.md F7; DECISIONS.md D68–D70, D73). It only reads state, so it takes no lock.
 */
public final class ReportCommand implements CliCommand {
  static final String DEFAULT_OUT = "report.html";

  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;

  public ReportCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    Path out;
    try {
      if (args.isEmpty()) {
        out = Paths.get(DEFAULT_OUT);
      } else if (args.size() == 2 && args.get(0).equals("--out")) {
        out = Paths.get(args.get(1));
      } else {
        context.err().println("draftwatch report: expected [--out report.html]; see --help");
        return Cli.EXIT_USAGE;
      }
    } catch (InvalidPathException e) {
      context.err().println("draftwatch report: invalid --out path: " + e.getMessage());
      return Cli.EXIT_USAGE;
    }
    DraftwatchConfig config;
    try {
      config = loader.load(context.configFile());
    } catch (ConfigException e) {
      context.err().println(e.getMessage());
      return Cli.EXIT_FAILURE;
    }
    Services s = services.apply(config);
    Path file = out.toAbsolutePath().normalize();
    ReportModel model;
    try {
      model =
          ReportModel.build(
              config.targets().stream().map(TargetConfig::name).collect(Collectors.toList()),
              s.results().all(),
              s.results()::locate,
              s.detections().all(),
              config.stateDir().resolve(FileDetectionLog.FILE));
      String html = HtmlReportRenderer.render(model, file, s.clock().instant());
      AtomicFiles.write(file, html.getBytes(StandardCharsets.UTF_8));
    } catch (StoreException e) {
      return context.fail(e.getMessage());
    } catch (IOException e) {
      return context.fail("cannot write " + file + ": " + e.getMessage());
    }
    int series = model.targets().stream().mapToInt(t -> t.series().size()).sum();
    context.out().println(
        "wrote " + file + ": " + model.resultCount() + " result(s) in " + series
            + " series of " + model.targets().size() + " target(s)");
    if (!model.configuredWithoutResults().isEmpty()) {
      context.out().println(
          "no results yet for: " + String.join(", ", model.configuredWithoutResults()));
    }
    return Cli.EXIT_OK;
  }
}
