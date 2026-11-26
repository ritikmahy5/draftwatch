package dev.draftwatch.scoring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The archive export. Every metric value in these files is synthetic. */
public class ArchiveExporterTest {
  private static final String PREFIX = "/scratch/someone";
  private static final String STATE = PREFIX + "/draftwatch-eval/state-h020";
  private static final String JOB = "j20261105T000000Z-00000001";
  private static final String REPORT = "{\"num_prompts\":2,\"decoding\":{\"temperature\":0}}";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path state;
  private Path out;
  private ArchiveExporter exporter;

  @Before
  public void setUp() throws IOException {
    state = tmp.newFolder("state").toPath();
    out = tmp.getRoot().toPath().resolve("out");
    exporter = new ArchiveExporter(PREFIX, "/scratch/user", new ObjectMapper());
    write("results/t-run1/result.json", result(REPORT));
    write("raw/" + JOB + "/attempt-1/report.json", REPORT);
    write("raw/" + JOB + "/attempt-1/stdout.log", "not exported " + PREFIX);
    write("baselines.json", "{\"t-run1\":{\"path\":\"" + PREFIX + "/x\"}}");
    write("detections.log", "{\"result_file\":\"" + PREFIX + "/x\"}\n");
    write("alerts.log", "alert at " + PREFIX + "/x\n");
  }

  private static String result(String report) {
    return "{\"provenance\":{\"checkpoint_path\":\"" + PREFIX + "/x\",\"raw_report_path\":\""
        + STATE + "/raw/" + JOB + "/attempt-1/report.json\"},\"report\":" + report + "}";
  }

  private void write(String relative, String content) throws IOException {
    Path p = state.resolve(relative);
    Files.createDirectories(p.getParent());
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
  }

  private String read(String relative) throws IOException {
    return new String(Files.readAllBytes(out.resolve(relative)), StandardCharsets.UTF_8);
  }

  private List<String> written() throws IOException {
    try (Stream<Path> files = Files.walk(out)) {
      return files
          .filter(Files::isRegularFile)
          .map(p -> out.relativize(p).toString())
          .sorted()
          .collect(Collectors.toList());
    }
  }

  @Test
  public void replacesPrefixInEveryFile() throws IOException {
    ExportSummary summary = exporter.export(state, out);
    assertEquals(
        List.of(
            "alerts.log",
            "baselines.json",
            "detections.log",
            "raw/" + JOB + "/attempt-1/report.json",
            "results/t-run1/result.json"),
        written());
    assertEquals(5, summary.written().size());
    for (String f : written()) {
      String text = read(f);
      assertFalse(f, text.contains("someone"));
    }
    assertTrue(read("baselines.json").contains("/scratch/user/x"));
    assertTrue(read("detections.log").contains("/scratch/user/x"));
    assertTrue(read("alerts.log").contains("/scratch/user/x"));
    assertTrue(
        read("results/t-run1/result.json")
            .contains("/scratch/user/draftwatch-eval/state-h020/raw/" + JOB));
  }

  @Test
  public void copiesRawReportOfEachResult() throws IOException {
    exporter.export(state, out);
    assertEquals(REPORT, read("raw/" + JOB + "/attempt-1/report.json"));
  }

  @Test
  public void skipsIdenticalFinderDuplicate() throws IOException {
    write("alerts 2.log", "alert at " + PREFIX + "/x\n");
    ExportSummary summary = exporter.export(state, out);
    assertEquals(List.of(state.resolve("alerts 2.log")), summary.skippedDuplicates());
    assertFalse(Files.exists(out.resolve("alerts 2.log")));
  }

  @Test
  public void skipsIdenticalFinderDuplicateOfAResult() throws IOException {
    write("results/t-run1/result 2.json", result(REPORT));
    ExportSummary summary = exporter.export(state, out);
    assertEquals(
        List.of(state.resolve("results/t-run1/result 2.json")), summary.skippedDuplicates());
    assertFalse(Files.exists(out.resolve("results/t-run1/result 2.json")));
  }

  @Test
  public void failsOnDifferingFinderDuplicate() throws IOException {
    write("alerts 2.log", "alert at " + PREFIX + "/y\n");
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains("alerts 2.log"));
  }

  @Test
  public void failsWhenEmbeddedReportDiffersFromRaw() throws IOException {
    write("raw/" + JOB + "/attempt-1/report.json", "{\"num_prompts\":3}");
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains("result.json"));
  }

  @Test
  public void numericallyEqualReportsMatch() throws IOException {
    write(
        "raw/" + JOB + "/attempt-1/report.json",
        "{\"num_prompts\":2,\"decoding\":{\"temperature\":0.0}}");
    exporter.export(state, out);
    assertTrue(Files.exists(out.resolve("results/t-run1/result.json")));
  }

  @Test
  public void failsWhenRawReportIsMissing() throws IOException {
    Files.delete(state.resolve("raw/" + JOB + "/attempt-1/report.json"));
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains("report.json"));
  }

  @Test
  public void failsWhenUsernameSurvives() throws IOException {
    write("alerts.log", "submitted by someone at 10:00\n");
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains("alerts.log"));
  }

  @Test
  public void doesNotRewriteALongerUserName() throws IOException {
    write("alerts.log", "alert at /scratch/someoneelse/x\n");
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains("alerts.log"));
  }

  @Test
  public void copiesExtraFilesWithTheSameReplacement() throws IOException {
    Path extra = tmp.newFile("sacct-elapsed.txt").toPath();
    Files.write(extra, "JobName|Elapsed|State\n".getBytes(StandardCharsets.UTF_8));
    exporter.export(state, out, List.of(extra));
    assertArrayEquals(Files.readAllBytes(extra), Files.readAllBytes(out.resolve("sacct-elapsed.txt")));
  }

  @Test
  public void copiesAnExtraDirectoryUnderTheGivenName() throws IOException {
    Path dir = tmp.newFolder("p2").toPath();
    Files.write(dir.resolve("qwen-chat-d1.json"), "{\"n\":1}".getBytes(StandardCharsets.UTF_8));
    Files.write(dir.resolve("qwen-chat-d1 2.json"), "{\"n\":1}".getBytes(StandardCharsets.UTF_8));
    ExportSummary summary =
        exporter.export(state, out, List.of(), java.util.Map.of("pilot-p2", dir));
    assertEquals("{\"n\":1}", read("pilot-p2/qwen-chat-d1.json"));
    assertFalse(Files.exists(out.resolve("pilot-p2/qwen-chat-d1 2.json")));
    assertTrue(summary.skippedDuplicates().contains(dir.resolve("qwen-chat-d1 2.json")));
  }

  @Test
  public void refusesNonEmptyDestination() throws IOException {
    Files.createDirectories(out);
    Files.write(out.resolve("old.txt"), new byte[] {1});
    ExportException e = assertThrows(ExportException.class, () -> exporter.export(state, out));
    assertTrue(e.getMessage(), e.getMessage().contains(out.toString()));
  }
}
