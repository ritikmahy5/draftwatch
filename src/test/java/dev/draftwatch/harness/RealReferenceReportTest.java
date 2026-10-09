package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.domain.WireNamed;
import dev.draftwatch.stats.MetricCalculator;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * A report the reference harness wrote on a real GPU run passes
 * {@link ReportParser}, checked against the arguments that run was given (recorded beside it as
 * {@code real_<job>_run*.json}, where the cluster username in paths is replaced by {@code user}).
 * Skipped until such a run has been committed.
 */
public class RealReferenceReportTest {
  private static final Path FIXTURES = Paths.get("src/test/resources/fixtures");
  private static final Pattern REPORT = Pattern.compile("real_(\\d+)_report(_[a-z]+)?\\.json");
  /** The prompt file of the committed A100 run, committed byte for byte. */
  static final Path PROMPTS = FIXTURES.resolve("acceptance_prompts.jsonl");

  private final ObjectMapper json = new ObjectMapper();

  private static List<Path> reports() throws IOException {
    try (Stream<Path> files = Files.list(FIXTURES)) {
      return files
          .filter(f -> REPORT.matcher(f.getFileName().toString()).matches())
          .sorted()
          .collect(Collectors.toList());
    }
  }

  /** What the engine would have expected, from the run's recorded arguments. */
  private ExpectedReport expected(JsonNode run) throws IOException {
    JsonNode d = run.get("decoding");
    List<Integer> seeds = new ArrayList<>();
    run.get("seeds").forEach(s -> seeds.add(s.intValue()));
    Probe probe =
        Probe.of(
            "acceptance",
            Draft.of(
                run.get("draft_id").textValue(),
                Paths.get(run.get("draft_path").textValue()),
                DraftStructure.CHAIN),
            PROMPTS.toAbsolutePath(),
            Decoding.of(
                new BigDecimal(d.get("temperature").asText()),
                d.get("max_new_tokens").intValue(),
                d.get("num_speculative_tokens").intValue(),
                d.get("dtype").textValue()),
            WireNamed.parse(Estimator.class, run.get("estimator").textValue()).orElseThrow(),
            seeds);
    PromptSet prompts = new PromptSetReader(json).read(PROMPTS.toAbsolutePath());
    String draftFingerprint = "sampled-" + "0".repeat(64); // not part of the report's checks
    return ExpectedReport.of(
        ResolvedProbe.of(
            probe, draftFingerprint, prompts,
            ProbeHasher.hash(probe, draftFingerprint, prompts.sha256())),
        CheckpointType.FULL);
  }

  @Test
  public void everyRealReportPassesReportParser() throws IOException {
    List<Path> reports = reports();
    assumeFalse("no real_<job>_report.json from a GPU run yet",
        reports.isEmpty());
    for (Path report : reports) {
      Matcher m = REPORT.matcher(report.getFileName().toString());
      assertTrue(m.matches());
      String suffix = m.group(2) == null ? "" : m.group(2);
      JsonNode run = json.readTree(FIXTURES.resolve("real_" + m.group(1) + "_run" + suffix
          + ".json").toFile());
      AcceptanceReport parsed =
          new ReportParser(new MetricCalculator()).parse(report, expected(run));
      assertTrue(report + ": " + parsed.backend(), parsed.backend().startsWith("vllm=="));
      assertEquals(report.toString(), 20, parsed.numPrompts());
    }
  }
}
