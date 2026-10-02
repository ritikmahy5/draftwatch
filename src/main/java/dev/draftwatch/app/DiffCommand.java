package dev.draftwatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.ConfigException;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.report.DiffRenderer;
import dev.draftwatch.report.MeasurementDiff;
import dev.draftwatch.store.StoreException;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code draftwatch diff <ckptA> <ckptB> --probe <id>}: two stored results side by side, and every
 * provenance field that differs (SPEC.md F7; DECISIONS.md D71). A checkpoint is named by its path
 * and matched against stored results, so it may have been deleted since. Read-only: no lock.
 */
public final class DiffCommand implements CliCommand {
  private final ConfigLoader loader;
  private final Function<DraftwatchConfig, Services> services;
  private final ObjectMapper json = new ObjectMapper();

  public DiffCommand(ConfigLoader loader, Function<DraftwatchConfig, Services> services) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public int run(CommandContext context, List<String> args) {
    List<String> paths = new ArrayList<>();
    String probe = null;
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals("--probe") && i + 1 < args.size() && probe == null) {
        probe = args.get(++i);
      } else if (!args.get(i).startsWith("--")) {
        paths.add(args.get(i));
      } else {
        paths.clear();
        break;
      }
    }
    if (paths.size() != 2 || probe == null) {
      context.err().println("draftwatch diff: expected <ckptA> <ckptB> --probe <id>; see --help");
      return Cli.EXIT_USAGE;
    }
    Path pathA;
    Path pathB;
    try {
      pathA = Paths.get(paths.get(0)).toAbsolutePath().normalize();
      pathB = Paths.get(paths.get(1)).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      context.err().println("draftwatch diff: invalid checkpoint path: " + e.getMessage());
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
    try {
      List<Measurement> all = s.results().all();
      MeasurementDiff.Side a = side(s, all, pathA, probe);
      MeasurementDiff.Side b = side(s, all, pathB, probe);
      context.out().print(DiffRenderer.render(MeasurementDiff.of(a, b)));
      return Cli.EXIT_OK;
    } catch (NoResultException e) {
      return context.fail(e.getMessage());
    } catch (StoreException e) {
      return context.fail(e.getMessage());
    }
  }

  /** No stored result matches a checkpoint path and probe. */
  private static final class NoResultException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NoResultException(String message) {
      super(message);
    }
  }

  /** The latest result of probe {@code probe} at {@code path} (D71). */
  private MeasurementDiff.Side side(
      Services s, List<Measurement> all, Path path, String probe) {
    List<Measurement> atPath =
        all.stream()
            .filter(m -> m.provenance().checkpoint().path().equals(path))
            .collect(Collectors.toList());
    List<Measurement> matching =
        atPath.stream()
            .filter(m -> m.provenance().probeId().equals(probe))
            .sorted(
                Comparator.comparing((Measurement m) -> m.provenance().endTime())
                    .thenComparing(Measurement::jobId))
            .collect(Collectors.toList());
    if (matching.isEmpty()) {
      throw new NoResultException(noResult(all, atPath, path, probe));
    }
    Measurement latest = matching.get(matching.size() - 1);
    List<String> others =
        matching.subList(0, matching.size() - 1).stream()
            .map(Measurement::jobId)
            .collect(Collectors.toList());
    Path file = s.results().locate(latest);
    try {
      return MeasurementDiff.Side.of(latest, file, json.readTree(file.toFile()), others);
    } catch (IOException e) {
      throw new StoreException(file, "cannot read result: " + e.getMessage(), e);
    }
  }

  private static String noResult(
      List<Measurement> all, List<Measurement> atPath, Path path, String probe) {
    if (!atPath.isEmpty()) {
      TreeSet<String> probes = new TreeSet<>();
      atPath.forEach(m -> probes.add(m.provenance().probeId()));
      return "no result for probe '" + probe + "' at " + path + "; it has results for probes: "
          + String.join(", ", probes);
    }
    TreeSet<String> sameName = new TreeSet<>();
    for (Measurement m : all) {
      Path stored = m.provenance().checkpoint().path();
      if (stored.getFileName() != null && stored.getFileName().equals(path.getFileName())) {
        sameName.add(stored.toString());
      }
    }
    return "no stored result for checkpoint " + path
        + (sameName.isEmpty()
            ? ""
            : "; stored checkpoints with that name: " + String.join(", ", sameName));
  }
}
