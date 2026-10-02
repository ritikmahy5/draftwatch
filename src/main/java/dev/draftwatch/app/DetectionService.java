package dev.draftwatch.app;

import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.TargetConfig;
import dev.draftwatch.detect.Comparability;
import dev.draftwatch.detect.DetectorSuite;
import dev.draftwatch.detect.DetectorVerdict;
import dev.draftwatch.detect.RegressionDetector;
import dev.draftwatch.domain.Baseline;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.events.BaselinePinned;
import dev.draftwatch.events.DetectionDeferred;
import dev.draftwatch.events.DetectionEvent;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.EventBus;
import dev.draftwatch.events.MeasurementStored;
import dev.draftwatch.store.BaselineRepository;
import dev.draftwatch.store.DetectionLog;
import dev.draftwatch.store.DetectionRecord;
import dev.draftwatch.store.ResultRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs regression detection when a measurement is stored (subscriber of
 * {@link MeasurementStored}) and publishes one event per outcome (DECISIONS.md D44, D50).
 *
 * <ul>
 *   <li>The target's baseline comes from the baseline repository; a target without one gets its
 *       first measured checkpoint, pinned automatically.
 *   <li>The baseline measurement is the latest result of the baseline checkpoint under the same
 *       probe hash, other than the current job, preferring a comparable one.
 *   <li>If the baseline checkpoint has no such result, detection is DEFERRED and runs when the
 *       baseline is measured.
 *   <li>A detector that already has a verdict for a job in the detection log is not run again for
 *       it, so re-publishing a measurement after a crash adds no duplicate outcomes.
 * </ul>
 */
public final class DetectionService {
  private final DraftwatchConfig config;
  private final Function<TargetConfig, DetectorSuite> suites;
  private final ResultRepository results;
  private final BaselineRepository baselines;
  private final DetectionLog log;
  private final EventBus bus;
  private final Clock clock;

  public DetectionService(
      DraftwatchConfig config,
      Function<TargetConfig, DetectorSuite> suites,
      ResultRepository results,
      BaselineRepository baselines,
      DetectionLog log,
      EventBus bus,
      Clock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.suites = Objects.requireNonNull(suites, "suites");
    this.results = Objects.requireNonNull(results, "results");
    this.baselines = Objects.requireNonNull(baselines, "baselines");
    this.log = Objects.requireNonNull(log, "log");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Detects regressions in a newly stored measurement and, if it measured the baseline
   * checkpoint, runs the detections that were waiting for it.
   *
   * @throws IllegalStateException if the measurement's target is no longer configured
   */
  public void onMeasurementStored(MeasurementStored event) {
    Measurement m = event.measurement();
    TargetConfig target =
        config
            .target(m.provenance().targetName())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "target " + m.provenance().targetName() + " is not configured"));
    Baseline baseline = baselines.get(target.name()).orElseGet(() -> pin(m));
    detect(m, target, baseline);
    if (m.fingerprint().equals(baseline.fingerprint())) {
      for (Measurement waiting : pending(target.name(), m.probeHash())) {
        detect(waiting, target, baseline);
      }
    }
  }

  private Baseline pin(Measurement m) {
    Baseline baseline =
        Baseline.of(m.provenance().checkpoint(), clock.instant(), Baseline.Source.AUTO);
    baselines.set(baseline);
    bus.publish(new BaselinePinned(clock.instant(), baseline));
    return baseline;
  }

  private void detect(Measurement m, TargetConfig target, Baseline baseline) {
    DetectionSubject subject = DetectionSubject.of(m, results.locate(m));
    List<Measurement> candidates = new ArrayList<>();
    for (Measurement r : results.find(baseline.fingerprint(), m.probeHash())) {
      if (!r.jobId().equals(m.jobId())) {
        candidates.add(r);
      }
    }
    boolean isBaseline = m.fingerprint().equals(baseline.fingerprint());
    if (candidates.isEmpty() && !isBaseline) {
      bus.publish(
          new DetectionDeferred(
              clock.instant(),
              subject,
              "the baseline checkpoint (step " + baseline.step() + ", " + baseline.path()
                  + ") has no result for probe " + m.provenance().probeId()
                  + " yet; detection runs when it does"));
      return;
    }
    Optional<Measurement> base = choose(m, candidates);
    Set<String> done = verdictsRecorded(m.jobId());
    List<RegressionDetector> toRun = new ArrayList<>();
    for (RegressionDetector d : suites.apply(target).detectors()) {
      if (!done.contains(d.describe())) {
        toRun.add(d);
      }
    }
    List<Measurement> history = results.history(target.name(), m.probeHash());
    for (DetectorVerdict v : new DetectorSuite(toRun).run(m, base, history)) {
      bus.publish(DetectionEvent.of(clock.instant(), subject, v));
    }
  }

  /** The latest comparable candidate, else the latest one (whose incomparability is reported). */
  private static Optional<Measurement> choose(Measurement m, List<Measurement> oldestFirst) {
    for (int i = oldestFirst.size() - 1; i >= 0; i--) {
      if (Comparability.comparable(m, oldestFirst.get(i))) {
        return Optional.of(oldestFirst.get(i));
      }
    }
    return oldestFirst.isEmpty()
        ? Optional.empty()
        : Optional.of(oldestFirst.get(oldestFirst.size() - 1));
  }

  /** Detectors that already have a verdict (not DEFERRED) for {@code jobId}. */
  private Set<String> verdictsRecorded(String jobId) {
    Set<String> done = new HashSet<>();
    for (DetectionRecord r : log.all()) {
      if (r.jobId().equals(jobId) && r.detector().isPresent()) {
        done.add(r.detector().get());
      }
    }
    return done;
  }

  /** Measurements of {@code target} under {@code probeHash} whose last log record is DEFERRED. */
  private List<Measurement> pending(String target, String probeHash) {
    Map<String, String> lastKind = new LinkedHashMap<>();
    for (DetectionRecord r : log.all()) {
      if (r.target().equals(target) && r.probeHash().equals(probeHash)) {
        lastKind.put(r.jobId(), r.kind());
      }
    }
    List<Measurement> waiting = new ArrayList<>();
    for (Measurement m : results.history(target, probeHash)) {
      if ("DEFERRED".equals(lastKind.get(m.jobId()))) {
        waiting.add(m);
      }
    }
    return waiting;
  }
}
