package dev.draftwatch.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import dev.draftwatch.domain.AcceptanceReport;
import dev.draftwatch.domain.SeedReport;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.FakeHarness;
import dev.draftwatch.testing.ReportScenario;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The reference harness ({@code python/measure_acceptance.py}) with its vLLM backend replaced by
 * recorded counter snapshots, invoked with the exact arguments the engine passes: its report
 * must pass {@link ReportParser}, the contract's authority (DECISIONS.md D85, D86). The
 * snapshots are synthetic until the GPU run records real ones.
 */
public class ReferenceHarnessReplayTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void aReplayedRunsReportPassesReportParser() {
    ReportScenario s = ReportScenario.greedy(tmp.getRoot().toPath());
    Path project = FakeHarness.projectDir();
    List<String> command =
        List.of(
            "env",
            "DRAFTWATCH_REPLAY="
                + project.resolve("src/test/resources/fixtures/synthetic_counter_snapshots.json"),
            "python3",
            project.resolve("python/tests/replay_harness.py").toString());
    HarnessInvocation invocation =
        HarnessInvocation.builder()
            .harnessCommand(command)
            .checkpoint(s.checkpoint())
            .probe(s.probe().probe())
            .out(s.reportPath())
            .build();
    assertEquals(0, FakeHarness.run(invocation.command(), s.dir()));

    AcceptanceReport report =
        new ReportParser(new MetricCalculator()).parse(s.reportPath(), s.expected());
    assertEquals("vllm==replay", report.backend());
    SeedReport seed = report.seeds().get(0);
    assertEquals(9L, seed.totalSteps());
    assertEquals(26L, seed.totalProposed());
    assertEquals(12L, seed.totalAccepted());
    assertEquals(12.0 / 26.0, seed.alpha(), 0.0);
    assertFalse("prompt 2 proposed 8 of 9 positions", seed.positionCountsExact());
  }
}
