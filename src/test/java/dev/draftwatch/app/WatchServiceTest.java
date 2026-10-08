package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.discovery.CheckpointInspector;
import dev.draftwatch.discovery.Discovery;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.exec.ExecutorStatus;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.RetryPolicy;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import dev.draftwatch.harness.PromptSetReader;
import dev.draftwatch.harness.ProbeResolver;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.InMemoryBaselineRepository;
import dev.draftwatch.testing.InMemoryJobRepository;
import dev.draftwatch.testing.InMemoryResultRepository;
import dev.draftwatch.testing.ScriptedCheckpointSource;
import dev.draftwatch.testing.ScriptedExecutor;
import dev.draftwatch.trigger.RepositoryHistory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * One {@code watch} pass over a scripted checkpoint source and executor (DECISIONS.md D55), so
 * what the source reports is chosen exactly, with no checkpoint directories or timing.
 */
public class WatchServiceTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final InMemoryJobRepository jobs = new InMemoryJobRepository();
  private final InMemoryResultRepository results = new InMemoryResultRepository();
  private final ScriptedCheckpointSource source = new ScriptedCheckpointSource();
  private final ScriptedExecutor executor =
      new ScriptedExecutor().then(ExecutorStatus.running(Optional.empty()));
  private Path dir;

  @Before
  public void setUp() throws Exception {
    dir = tmp.getRoot().toPath();
    Files.createDirectories(dir.resolve("draft"));
    Files.write(dir.resolve("draft/model.safetensors"), new byte[4096]);
    Files.writeString(dir.resolve("prompts.jsonl"), "{\"p\": 1}\n{\"p\": 2}\n");
  }

  private WatchService watch(String probes, String targetProbes) throws Exception {
    String yaml =
        String.join(
            "\n",
            "executor: { type: local, max_retries: 0 }",
            "harness: { command: [h] }",
            "probes:",
            probes,
            "targets:",
            "  - { name: run, checkpoint_dirs: [r], checkpoint_type: full,",
            "      probes: [" + targetProbes + "],",
            "      triggers: [not_already_measured: {}, max_pending: 2, always_final: {},",
            "                 every_n_steps: 1] }");
    ObjectMapper mapper = new YAMLMapper();
    DraftwatchConfig config =
        new ConfigValidator().validate(mapper.readTree(yaml), dir.resolve("draftwatch.yaml"));
    Clock clock = Clock.systemUTC();
    SampledBlockFingerprinter fingerprinter = new SampledBlockFingerprinter();
    int[] counter = {0};
    MeasurementRunner runner =
        new MeasurementRunner(
            executor,
            new JobPoller(executor, new ReportParser(new MetricCalculator()), clock),
            new RetryPolicy(0),
            jobs,
            results,
            new EventBus(message -> {}, clock),
            () -> "job-" + (++counter[0]),
            clock,
            dir.resolve("state"),
            List.of("h"),
            dir);
    return new WatchService(
        config,
        t -> source,
        Bootstrap::triggerChain,
        new ProbeResolver(fingerprinter, new PromptSetReader(new ObjectMapper())),
        runner,
        new RepositoryHistory(jobs, results),
        new InMemoryBaselineRepository(),
        new CheckpointInspector(fingerprinter, new ObjectMapper(), clock),
        Bootstrap::completionPolicy,
        jobs,
        clock);
  }

  private static String probe(String id, String draftPath) {
    return "  - { id: " + id + ", draft: { id: d, path: " + draftPath + ", structure: chain },"
        + " prompts: { path: prompts.jsonl }, decoding: { temperature: 0, max_new_tokens: 64,"
        + " num_speculative_tokens: 4, dtype: bfloat16 }, seeds: [0] }";
  }

  private static Checkpoint checkpoint(long step) {
    return Checkpoint.builder()
        .targetName("run")
        .path(Path.of("/runs/checkpoint-" + step))
        .step(step)
        .fingerprint("sampled-" + String.format("%064x", step))
        .type(CheckpointType.FULL)
        .build();
  }

  private static List<Long> steps(PassReport report) {
    return report.submitted().stream()
        .map(s -> s.job().spec().checkpoint().step())
        .collect(Collectors.toList());
  }

  @Test
  public void maxPendingCountsJobsSubmittedEarlierInThePass() throws Exception {
    WatchService watch = watch(probe("chat", "draft"), "chat");
    source.returns(List.of(checkpoint(100), checkpoint(200), checkpoint(300)), List.of());
    PassReport first = watch.pass();
    assertEquals(List.of(100L, 200L), steps(first));
    assertEquals(Map.of("max_pending(2)", 1), first.notMeasuredByRule());
    assertEquals(2, first.stillActive());
    PassReport second = watch.pass();
    assertEquals(List.of(), steps(second));
    assertEquals(
        Map.of("not_already_measured", 2, "max_pending(2)", 1), second.notMeasuredByRule());
    assertEquals(2, source.polls());
    assertEquals(2, executor.submitted().size());
  }

  @Test
  public void anUnresolvableProbeIsAnErrorAndTheOtherProbeIsStillMeasured() throws Exception {
    WatchService watch =
        watch(probe("chat", "draft") + "\n" + probe("gone", "missing-draft"), "chat, gone");
    source.returns(List.of(checkpoint(100)), List.of());
    PassReport report = watch.pass();
    assertEquals(1, report.errors().size());
    assertTrue(report.errors().get(0), report.errors().get(0).startsWith("probe gone cannot"));
    assertEquals(List.of(100L), steps(report));
    assertEquals("chat", report.submitted().get(0).job().spec().probe().probe().id());
  }

  @Test
  public void everySkippedPathIsReportedAndNothingIsSubmitted() throws Exception {
    WatchService watch = watch(probe("chat", "draft"), "chat");
    List<Discovery.Skipped> skipped =
        List.of(
            new Discovery.Skipped(
                Path.of("/runs/checkpoint-400"), Discovery.SkipKind.INCOMPLETE, "being written"),
            new Discovery.Skipped(
                Path.of("/runs/notes"), Discovery.SkipKind.REJECTED, "no weight files"));
    source.returns(List.of(), skipped);
    PassReport report = watch.pass();
    assertEquals(skipped, report.skipped());
    assertEquals(List.of(), report.submitted());
    assertEquals(List.of(), report.errors());
  }
}
