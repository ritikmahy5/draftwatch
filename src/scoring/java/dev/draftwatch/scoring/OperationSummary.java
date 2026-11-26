package dev.draftwatch.scoring;

import dev.draftwatch.store.DetectionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How the live watch behaved (docs/EVALUATION.md, question 1): whether any checkpoint was
 * measured more than once, which expected measurements are missing, and every alarm on a live
 * probe next to what the reference probe says about that checkpoint.
 */
public final class OperationSummary {
  static final String NO_REFERENCE = "no reference";

  private final int measurementsPerCheckpointMax;
  private final List<String> remeasured;
  private final List<MissingMeasurement> missing;
  private final List<LiveFlag> liveFlags;

  private OperationSummary(
      int max, List<String> remeasured, List<MissingMeasurement> missing, List<LiveFlag> flags) {
    this.measurementsPerCheckpointMax = max;
    this.remeasured = List.copyOf(remeasured);
    this.missing = List.copyOf(missing);
    this.liveFlags = List.copyOf(flags);
  }

  /**
   * @param rowsByReferenceProbe ground-truth rows keyed by reference probe id, such as {@code
   *     qwen-chat}
   */
  public static OperationSummary of(
      EvaluationData data,
      List<DetectionRecord> log,
      Map<String, List<CheckpointRow>> rowsByReferenceProbe) {
    Map<String, Set<String>> jobsByCheckpoint = new TreeMap<>();
    List<LiveFlag> flags = new ArrayList<>();
    for (DetectionRecord r : log) {
      String probe = r.subject().probeId();
      long step = r.subject().step();
      jobsByCheckpoint
          .computeIfAbsent(r.target() + " " + probe + " step " + step, k -> new TreeSet<>())
          .add(r.jobId());
      ProbeName name = EvaluationNames.probe(probe);
      if (!r.kind().equals("REGRESSION") || name.kind().isReference()) {
        continue;
      }
      ProbeKind reference = name.kind() == ProbeKind.CHAT_LIVE ? ProbeKind.CHAT : ProbeKind.CODE;
      Run run = EvaluationNames.target(r.target()).run();
      String label = NO_REFERENCE;
      for (CheckpointRow row :
          rowsByReferenceProbe.getOrDefault(
              name.family() + "-" + reference.suffix(), List.of())) {
        if (row.run() == run && row.step() == step) {
          label = row.label().name();
        }
      }
      flags.add(new LiveFlag(r.target(), probe, step, r.detector().orElse("none"), r.jobId(),
          label));
    }
    int max = 0;
    List<String> remeasured = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : jobsByCheckpoint.entrySet()) {
      max = Math.max(max, e.getValue().size());
      if (e.getValue().size() > 1) {
        remeasured.add(e.getKey() + ": jobs " + String.join(", ", e.getValue()));
      }
    }
    return new OperationSummary(max, remeasured, data.missing(), flags);
  }

  /** The most jobs logged for one target, probe, and checkpoint; 1 if nothing was re-measured. */
  public int measurementsPerCheckpointMax() {
    return measurementsPerCheckpointMax;
  }

  /** Checkpoints with more than one logged job, with their job ids. */
  public List<String> remeasured() {
    return remeasured;
  }

  public List<MissingMeasurement> missing() {
    return missing;
  }

  /** In log order. */
  public List<LiveFlag> liveFlags() {
    return liveFlags;
  }

  @Override
  public String toString() {
    return "max " + measurementsPerCheckpointMax + " per checkpoint, remeasured " + remeasured
        + ", missing " + missing + ", " + liveFlags.size() + " live flags";
  }
}
