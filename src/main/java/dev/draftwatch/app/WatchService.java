package dev.draftwatch.app;

import dev.draftwatch.config.CompletionSpec;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.discovery.CheckpointInspector;
import dev.draftwatch.discovery.CheckpointRejectedException;
import dev.draftwatch.discovery.CheckpointSource;
import dev.draftwatch.discovery.CompletionPolicy;
import dev.draftwatch.discovery.Discovery;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.exec.Job;
import dev.draftwatch.fingerprint.FingerprintException;
import dev.draftwatch.harness.ProbeResolver;
import dev.draftwatch.harness.PromptSetException;
import dev.draftwatch.store.BaselineRepository;
import dev.draftwatch.store.JobRepository;
import dev.draftwatch.trigger.History;
import dev.draftwatch.trigger.TriggerChain;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One {@code watch} pass (ARCHITECTURE.md, "Concurrency"; DECISIONS.md D55), run under the state
 * lock by the caller:
 *
 * <ol>
 *   <li>A baseline checkpoint without a result or job for a probe is measured (SPEC.md F5),
 *       bypassing trigger rules but never duplicating a job.
 *   <li>Each target's checkpoint source is polled; every complete checkpoint, in step order, goes
 *       through the target's trigger chain once per probe, and accepted ones are submitted.
 *   <li>Every unfinished job is advanced once: polled, its result stored and detected, or
 *       retried.
 * </ol>
 */
public final class WatchService {
  private final DraftwatchConfig config;
  private final Function<TargetConfig, CheckpointSource> sources;
  private final Function<TargetConfig, TriggerChain> chains;
  private final ProbeResolver resolver;
  private final MeasurementRunner runner;
  private final History history;
  private final BaselineRepository baselines;
  private final CheckpointInspector inspector;
  private final Function<CompletionSpec, CompletionPolicy> completion;
  private final JobRepository jobs;
  private final Clock clock;

  public WatchService(
      DraftwatchConfig config,
      Function<TargetConfig, CheckpointSource> sources,
      Function<TargetConfig, TriggerChain> chains,
      ProbeResolver resolver,
      MeasurementRunner runner,
      History history,
      BaselineRepository baselines,
      CheckpointInspector inspector,
      Function<CompletionSpec, CompletionPolicy> completion,
      JobRepository jobs,
      Clock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.chains = Objects.requireNonNull(chains, "chains");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.history = Objects.requireNonNull(history, "history");
    this.baselines = Objects.requireNonNull(baselines, "baselines");
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.completion = Objects.requireNonNull(completion, "completion");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public PassReport pass() {
    List<String> errors = new ArrayList<>();
    Map<String, ResolvedProbe> probes = resolveProbes(errors);
    List<Job> baselineSubmitted = new ArrayList<>();
    List<Discovery.Skipped> skipped = new ArrayList<>();
    List<PassReport.Submitted> submitted = new ArrayList<>();
    Map<String, Integer> notMeasured = new TreeMap<>();
    for (TargetConfig target : config.targets()) {
      measureBaseline(target, probes, baselineSubmitted, errors);
      Discovery discovery = sources.apply(target).poll();
      skipped.addAll(discovery.skipped());
      TriggerChain chain = chains.apply(target);
      for (Checkpoint checkpoint : discovery.checkpoints()) {
        for (String probeId : target.probeIds()) {
          ResolvedProbe probe = probes.get(probeId);
          if (probe == null) {
            continue; // reported once in errors
          }
          TriggerChain.Outcome outcome = chain.decide(checkpoint, probe, history);
          if (outcome.accepted()) {
            Job job = runner.submit(runner.create(checkpoint, probe));
            submitted.add(
                new PassReport.Submitted(
                    job, outcome.rule().map(r -> r + ": ").orElse("") + outcome.reason()));
          } else {
            notMeasured.merge(outcome.rule().orElse("?"), 1, Integer::sum);
          }
        }
      }
    }
    List<Job> finished = new ArrayList<>();
    int active = 0;
    for (Job job : jobs.all()) {
      if (runner.isDone(job)) {
        continue;
      }
      Job next = runner.advance(job);
      if (runner.isDone(next)) {
        finished.add(next);
      } else {
        active++;
      }
    }
    return new PassReport(
        clock.instant(),
        errors,
        skipped,
        baselineSubmitted,
        submitted,
        notMeasured,
        finished,
        active);
  }

  private Map<String, ResolvedProbe> resolveProbes(List<String> errors) {
    Map<String, ResolvedProbe> resolved = new LinkedHashMap<>();
    for (Probe probe : config.probes()) {
      try {
        resolved.put(probe.id(), resolver.resolve(probe));
      } catch (FingerprintException | PromptSetException e) {
        errors.add("probe " + probe.id() + " cannot be resolved: " + e.getMessage());
      }
    }
    return resolved;
  }

  private void measureBaseline(
      TargetConfig target,
      Map<String, ResolvedProbe> probes,
      List<Job> submitted,
      List<String> errors) {
    Baseline baseline = baselines.get(target.name()).orElse(null);
    if (baseline == null) {
      return; // the first measured checkpoint becomes the baseline
    }
    Checkpoint checkpoint = null;
    for (String probeId : target.probeIds()) {
      ResolvedProbe probe = probes.get(probeId);
      if (probe == null
          || history.hasResult(baseline.fingerprint(), probe.hash())
          || !history.jobs(baseline.fingerprint(), probe.hash()).isEmpty()) {
        continue;
      }
      if (checkpoint == null) {
        try {
          CompletionPolicy policy = completion.apply(target.completion());
          checkpoint = inspector.inspect(target.target(), baseline.path(), policy);
        } catch (CheckpointRejectedException e) {
          errors.add(
              "baseline of target " + target.name() + " cannot be measured: " + e.getMessage());
          return;
        }
        if (!checkpoint.fingerprint().equals(baseline.fingerprint())) {
          errors.add(
              "baseline of target " + target.name() + " at " + baseline.path()
                  + " has changed (fingerprint " + checkpoint.fingerprint() + ", baseline "
                  + baseline.fingerprint() + "); set it again with 'draftwatch baseline "
                  + target.name() + " <checkpoint>'");
          return;
        }
      }
      submitted.add(runner.submit(runner.create(checkpoint, probe)));
    }
  }
}
