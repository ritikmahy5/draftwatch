package dev.draftwatch.app;

import dev.draftwatch.config.CompletionSpec;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.discovery.CheckpointInspector;
import dev.draftwatch.discovery.CompletionPolicy;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.harness.ProbeResolver;
import dev.draftwatch.store.BaselineRepository;
import dev.draftwatch.store.DetectionLog;
import dev.draftwatch.store.JobRepository;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.store.StateLock;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The objects that depend on a loaded config, built by {@link Bootstrap}. A plain holder: it
 * creates nothing itself, so all wiring stays in {@code Bootstrap}.
 */
public final class Services {
  private final DraftwatchConfig config;
  private final ProbeResolver probeResolver;
  private final CheckpointInspector inspector;
  private final Function<CompletionSpec, CompletionPolicy> completionPolicies;
  private final JobRepository jobs;
  private final ResultRepository results;
  private final BaselineRepository baselines;
  private final DetectionLog detections;
  private final EventBus bus;
  private final StateLock stateLock;
  private final Supplier<MeasurementRunner> runner;
  private final Sleeper sleeper;
  private final Clock clock;

  Services(
      DraftwatchConfig config,
      ProbeResolver probeResolver,
      CheckpointInspector inspector,
      Function<CompletionSpec, CompletionPolicy> completionPolicies,
      JobRepository jobs,
      ResultRepository results,
      BaselineRepository baselines,
      DetectionLog detections,
      EventBus bus,
      StateLock stateLock,
      Supplier<MeasurementRunner> runner,
      Sleeper sleeper,
      Clock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.probeResolver = Objects.requireNonNull(probeResolver, "probeResolver");
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.completionPolicies = Objects.requireNonNull(completionPolicies, "completionPolicies");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.results = Objects.requireNonNull(results, "results");
    this.baselines = Objects.requireNonNull(baselines, "baselines");
    this.detections = Objects.requireNonNull(detections, "detections");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.stateLock = Objects.requireNonNull(stateLock, "stateLock");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public DraftwatchConfig config() {
    return config;
  }

  public ProbeResolver probeResolver() {
    return probeResolver;
  }

  public CheckpointInspector inspector() {
    return inspector;
  }

  public CompletionPolicy completionPolicy(CompletionSpec spec) {
    return completionPolicies.apply(spec);
  }

  public JobRepository jobs() {
    return jobs;
  }

  public ResultRepository results() {
    return results;
  }

  public BaselineRepository baselines() {
    return baselines;
  }

  public DetectionLog detections() {
    return detections;
  }

  /** The process's event bus, with every subscriber already registered by Bootstrap. */
  public EventBus bus() {
    return bus;
  }

  public StateLock stateLock() {
    return stateLock;
  }

  /**
   * The runner for the configured executor.
   *
   * @throws UnsupportedOperationException if the configured executor is not available yet
   */
  public MeasurementRunner runner() {
    return runner.get();
  }

  public Sleeper sleeper() {
    return sleeper;
  }

  public Clock clock() {
    return clock;
  }
}
