package dev.draftwatch.exec.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import dev.draftwatch.config.SlurmConfig;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.exec.LocalExecutor;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.testing.ReplayCommandRunner;
import dev.draftwatch.testing.ReportScenario;
import dev.draftwatch.testing.SettableClock;
import dev.draftwatch.testing.SlurmScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Every {@code real_} fixture recorded on the cluster by {@code scripts/record_slurm_fixtures.py},
 * replayed through {@link SlurmExecutor}: the output must parse, and a finished job must end in
 * the engine state that ARCHITECTURE.md's table gives for its final sacct state. The table is
 * written out again here, independently of {@link SlurmState}. Skipped until recordings exist
 * (DECISIONS.md D64).
 */
public class RecordedSlurmOutputTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static List<String> recorded() throws IOException {
    try (Stream<Path> files = Files.list(SlurmScenario.dir())) {
      return files
          .map(f -> f.getFileName().toString().replaceFirst("\\.json$", ""))
          .filter(n -> n.startsWith("real_"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  /** The final sacct line's state and exit code, as {@code STATE N:M}, if any. */
  private static Optional<String[]> finalSacct(SlurmScenario s) {
    SlurmScenario.Observation last = s.observations().get(s.observations().size() - 1);
    for (dev.draftwatch.exec.slurm.CommandResult r : last.results()) {
      if (r.argv().get(0).equals("sacct")) {
        for (String line : r.stdout().split("\n")) {
          String[] f = line.split("\\|", -1);
          if (f.length == 5 && f[0].trim().equals(s.jobId())) {
            return Optional.of(new String[] {f[1].trim().split("[\\s+]")[0], f[2].trim()});
          }
        }
      }
    }
    return Optional.empty();
  }

  /** ARCHITECTURE.md's table and D59: the expected end of a job that finished this way. */
  private static Optional<String> expected(String state, String exitCode) {
    String[] code = exitCode.split(":");
    int n = Integer.parseInt(code[0]);
    int signal = Integer.parseInt(code[1]);
    switch (state) {
      case "COMPLETED":
        return Optional.of("SUCCEEDED");
      case "FAILED":
        return Optional.of(
            signal != 0 || n == 0
                ? "FAILED unexpected_exit"
                : "FAILED " + FailureReason.forExitCode(n).wireName());
      case "OUT_OF_MEMORY":
        return Optional.of("FAILED out_of_memory");
      case "TIMEOUT":
      case "DEADLINE":
        return Optional.of("FAILED timeout");
      case "NODE_FAIL":
      case "BOOT_FAIL":
        return Optional.of("FAILED node_failure");
      case "PREEMPTED":
        return Optional.of("FAILED preempted_no_requeue");
      case "CANCELLED":
        return Optional.of("CANCELLED");
      default:
        return Optional.empty(); // not finished when recorded
    }
  }

  private static String describe(Job job) {
    return job.state() + job.failureReason().map(r -> " " + r.wireName()).orElse("");
  }

  @Test
  public void everyRecordingParsesAndFinishedJobsEndAsTheTableSays() throws IOException {
    List<String> names = recorded();
    assumeFalse("no real_ Slurm fixtures recorded yet (DECISIONS.md D64)", names.isEmpty());
    assertEquals(List.of(), mismatches(names));
  }

  /** The same check on the synthetic fixtures whose job finished, so the check itself is tested. */
  @Test
  public void theCheckAgreesOnTheSyntheticFixturesOfFinishedJobs() throws IOException {
    List<String> finished =
        List.of(
            "synthetic_completed",
            "synthetic_failed_exit_3",
            "synthetic_failed_signal",
            "synthetic_out_of_memory",
            "synthetic_timeout",
            "synthetic_deadline",
            "synthetic_node_fail",
            "synthetic_boot_fail",
            "synthetic_cancelled",
            "synthetic_preempt_requeue_complete",
            "synthetic_preempted_not_requeued",
            "synthetic_briefly_in_neither",
            "synthetic_sacct_lag",
            "synthetic_gone_with_empty_output",
            "synthetic_pending");
    assertEquals(List.of(), mismatches(finished));
  }

  private List<String> mismatches(List<String> names) throws IOException {
    List<String> mismatches = new ArrayList<>();
    for (String name : names) {
      SlurmScenario s = SlurmScenario.load(name);
      Path dir = tmp.newFolder(name).toPath();
      ReportScenario report = ReportScenario.greedy(dir);
      MeasurementSpec spec =
          MeasurementSpec.of(
              report.checkpoint(), report.probe(), report.harnessCommand(Map.of()), dir,
              dir.resolve("raw/j1"), SlurmExecutor.NAME);
      Path runDir = Files.createDirectories(spec.runDir(1));
      Optional<String[]> last = finalSacct(s);
      if (last.isPresent() && !last.get()[1].isEmpty()) {
        int n = Integer.parseInt(last.get()[1].split(":")[0]);
        Files.writeString(runDir.resolve(LocalExecutor.EXIT_FILE), n + "\n"); // the wrapper's
        if (n == 0) {
          assertEquals(0, report.run(Map.of()));
          Files.copy(report.reportPath(), spec.reportPath(1));
        }
      }
      ReplayCommandRunner commands = new ReplayCommandRunner();
      commands.always(
          dev.draftwatch.exec.slurm.CommandResult.of(List.of("scancel", s.jobId()), 0, "", ""));
      SettableClock clock = new SettableClock(Instant.EPOCH);
      SlurmConfig config =
          SlurmConfig.of(
              Optional.empty(), Optional.empty(), Optional.empty(), true, List.of(), List.of());
      JobPoller poller =
          new JobPoller(
              new SlurmExecutor(config, new SlurmCli(commands), clock, w -> {}),
              new ReportParser(new MetricCalculator()),
              clock);
      Instant first = s.observations().get(0).at();
      Job job =
          Job.created("j1", spec, first)
              .submitted(
                  JobHandle.of(SlurmExecutor.NAME, s.jobId(), runDir, first, Optional.empty()),
                  first);
      for (SlurmScenario.Observation o : s.observations()) {
        clock.set(o.at());
        commands.observe(o.results());
        job = poller.poll(job).job();
      }
      Optional<String> want = last.flatMap(l -> expected(l[0], l[1].isEmpty() ? "0:0" : l[1]));
      if (want.isPresent() && !want.get().equals(describe(job))) {
        mismatches.add(name + ": expected " + want.get() + ", got " + describe(job));
      }
      if (want.isEmpty() && job.state() != JobState.SUBMITTED && job.state() != JobState.RUNNING) {
        mismatches.add(name + ": unfinished when recorded, but ended " + describe(job));
      }
    }
    return mismatches;
  }
}
