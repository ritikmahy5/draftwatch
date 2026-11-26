package dev.draftwatch.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.store.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Copies a run's state directory into the repository with every cluster path replaced by a
 * placeholder (docs/EVALUATION.md, "Data handling"). It copies the results, {@code
 * baselines.json}, {@code detections.log}, {@code alerts.log}, each result's raw {@code
 * report.json}, and any extra files named by the caller, and nothing else.
 *
 * <p>Before writing anything it checks that each embedded report equals its raw report, that
 * every Finder duplicate ({@code <name> 2.<ext>}) of an exported file is byte-identical to it,
 * and that the cluster user name (the last segment of the prefix) appears nowhere in the output.
 */
public final class ArchiveExporter {
  private static final Pattern DUPLICATE = Pattern.compile("^(.*) 2(\\.[^.]*)?$");
  private static final List<String> TOP_LEVEL =
      List.of("baselines.json", "detections.log", "alerts.log");

  private final String clusterPrefix;
  private final String placeholder;
  private final String userName;
  private final Pattern prefixPattern;
  private final ObjectMapper json;

  /**
   * @param clusterPrefix the cluster directory that holds the user's files, such as {@code
   *     /scratch/<user>}; its last segment is the user name that must not survive
   * @param placeholder what the prefix becomes, such as {@code /scratch/user}
   */
  public ArchiveExporter(String clusterPrefix, String placeholder, ObjectMapper json) {
    this.clusterPrefix = stripTrailingSlash(Objects.requireNonNull(clusterPrefix, "clusterPrefix"));
    this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
    this.json = Objects.requireNonNull(json, "json");
    Path last = Paths.get(this.clusterPrefix).getFileName();
    if (last == null || last.toString().isEmpty()) {
      throw new IllegalArgumentException("cluster prefix has no last segment: " + clusterPrefix);
    }
    this.userName = last.toString();
    // Only a whole segment is replaced: "/scratch/ab" must not turn "/scratch/abc" into
    // "/scratch/userc"; the user-name scan then catches what was left.
    this.prefixPattern = Pattern.compile(Pattern.quote(this.clusterPrefix) + "(?![^/\"\\s])");
  }

  private static String stripTrailingSlash(String s) {
    return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  public ExportSummary export(Path stateDir, Path destination) {
    return export(stateDir, destination, List.of());
  }

  /**
   * Exports {@code stateDir} into {@code destination}, which must be missing or empty. {@code
   * extras} are copied into the destination's top level, with the same replacement and checks.
   *
   * @throws ExportException naming the file, if any check fails; nothing is written then
   */
  public ExportSummary export(Path stateDir, Path destination, List<Path> extras) {
    requireEmpty(destination);
    Map<Path, Path> sources = new LinkedHashMap<>(); // destination-relative -> source
    List<Path> skipped = new ArrayList<>();
    for (Path result : resultFiles(stateDir, skipped)) {
      sources.put(stateDir.relativize(result), result);
      Path raw = rawReport(stateDir, result);
      sources.put(stateDir.relativize(raw), raw);
    }
    for (String name : TOP_LEVEL) {
      Path file = stateDir.resolve(name);
      if (!Files.isRegularFile(file)) {
        throw new ExportException(file, "missing from the state directory");
      }
      sources.put(Paths.get(name), file);
    }
    for (Path extra : extras) {
      sources.put(extra.getFileName(), extra);
    }
    for (Path source : sources.values()) {
      duplicateOf(source).ifPresent(skipped::add);
    }
    Map<Path, byte[]> contents = new LinkedHashMap<>();
    for (Map.Entry<Path, Path> e : sources.entrySet()) {
      contents.put(e.getKey(), transform(e.getValue()));
    }
    List<Path> written = new ArrayList<>();
    for (Map.Entry<Path, byte[]> e : contents.entrySet()) {
      Path target = destination.resolve(e.getKey().toString());
      try {
        AtomicFiles.writeNew(target, e.getValue());
      } catch (IOException ex) {
        throw new ExportException(target, "cannot write: " + ex.getMessage(), ex);
      }
      written.add(target);
    }
    skipped.sort(Comparator.naturalOrder());
    return new ExportSummary(written, skipped);
  }

  private static void requireEmpty(Path destination) {
    if (!Files.exists(destination)) {
      return;
    }
    try (Stream<Path> list = Files.list(destination)) {
      if (list.findAny().isPresent()) {
        throw new ExportException(destination, "destination is not empty");
      }
    } catch (IOException e) {
      throw new ExportException(destination, "cannot list: " + e.getMessage(), e);
    }
  }

  /** Every result file, sorted; duplicates in {@code results/} are verified and listed. */
  private static List<Path> resultFiles(Path stateDir, List<Path> skipped) {
    Path results = stateDir.resolve("results");
    if (!Files.isDirectory(results)) {
      throw new ExportException(results, "missing from the state directory");
    }
    List<Path> files;
    try (Stream<Path> walk = Files.walk(results)) {
      files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new ExportException(results, "cannot list: " + e.getMessage(), e);
    }
    List<Path> out = new ArrayList<>();
    for (Path f : files) {
      Matcher m = DUPLICATE.matcher(f.getFileName().toString());
      if (m.matches()) {
        Path original = f.resolveSibling(m.group(1) + (m.group(2) == null ? "" : m.group(2)));
        if (!Files.isRegularFile(original)) {
          throw new ExportException(f, "looks like a Finder duplicate, but " + original
              + " does not exist");
        }
        continue; // verified and listed when its original is checked
      }
      if (!f.getFileName().toString().endsWith(".json")) {
        throw new ExportException(f, "unexpected file in results/");
      }
      out.add(f);
    }
    return out;
  }

  /** The archived raw report of {@code result}, checked against the embedded report. */
  private Path rawReport(Path stateDir, Path result) {
    JsonNode tree = readJson(result);
    JsonNode path = tree.path("provenance").path("raw_report_path");
    if (!path.isTextual()) {
      throw new ExportException(result, "provenance.raw_report_path is missing");
    }
    String clusterPath = path.asText();
    int raw = clusterPath.lastIndexOf("/raw/");
    if (!clusterPath.startsWith(clusterPrefix + "/") || raw < 0) {
      throw new ExportException(result, "raw_report_path " + clusterPath
          + " is not a raw/ path under " + clusterPrefix);
    }
    Path local = stateDir.resolve(clusterPath.substring(raw + 1));
    if (!Files.isRegularFile(local)) {
      throw new ExportException(result, "its raw report " + local + " is missing");
    }
    if (!tree.path("report").equals(NUMERIC_EQUALITY, readJson(local))) {
      throw new ExportException(result, "its embedded report differs from " + local);
    }
    return local;
  }

  /** Equal nodes, or numbers with the same value whatever their JSON type (0 and 0.0). */
  private static final Comparator<JsonNode> NUMERIC_EQUALITY =
      (a, b) -> {
        if (a.equals(b)) {
          return 0;
        }
        if (a.isNumber() && b.isNumber()) {
          return a.decimalValue().compareTo(b.decimalValue());
        }
        return 1;
      };

  private JsonNode readJson(Path file) {
    try {
      return json.readTree(file.toFile());
    } catch (IOException e) {
      throw new ExportException(file, "not valid JSON: " + e.getMessage(), e);
    }
  }

  /** The source's {@code <name> 2.<ext>} sibling, if it exists and is byte-identical. */
  private static java.util.Optional<Path> duplicateOf(Path source) {
    String name = source.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String copy = dot < 0 ? name + " 2" : name.substring(0, dot) + " 2" + name.substring(dot);
    Path sibling = source.resolveSibling(copy);
    if (!Files.exists(sibling)) {
      return java.util.Optional.empty();
    }
    if (!Arrays.equals(bytes(source), bytes(sibling))) {
      throw new ExportException(sibling, "differs from " + source.getFileName()
          + "; it cannot be skipped as a Finder duplicate");
    }
    return java.util.Optional.of(sibling);
  }

  private byte[] transform(Path source) {
    String text = new String(bytes(source), StandardCharsets.UTF_8);
    String replaced = prefixPattern.matcher(text).replaceAll(Matcher.quoteReplacement(placeholder));
    if (replaced.contains(userName)) {
      throw new ExportException(source, "still contains the cluster user name after replacing "
          + clusterPrefix);
    }
    return replaced.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new ExportException(file, "cannot read: " + e.getMessage(), e);
    }
  }
}
