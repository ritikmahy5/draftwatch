package dev.draftwatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Draft;
import dev.draftwatch.domain.DraftStructure;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.domain.WireNamed;
import dev.draftwatch.exec.FailureReason;
import dev.draftwatch.exec.Job;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobState;
import dev.draftwatch.exec.MeasurementSpec;
import dev.draftwatch.exec.StateChange;
import dev.draftwatch.harness.ReportJson;
import dev.draftwatch.harness.ReportViolationException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON for the files in the state directory: {@code jobs/<id>.json} and result files. The
 * mapping is written out field by field so the domain needs no serialization annotations, and a
 * stored report uses exactly the contract's schema.
 */
public final class JsonCodec {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  // --- jobs --------------------------------------------------------------------------------

  public ObjectNode jobJson(Job job) {
    ObjectNode node = NODES.objectNode();
    node.put("id", job.id());
    node.put("state", job.state().name());
    node.put("attempt", job.attempt());
    node.set("spec", specJson(job.spec()));
    if (job.handle().isPresent()) {
      node.set("handle", handleJson(job.handle().get()));
    } else {
      node.putNull("handle");
    }
    ArrayNode history = node.putArray("history");
    for (StateChange change : job.history()) {
      history.add(changeJson(change));
    }
    return node;
  }

  /**
   * Reads a job, replaying its history.
   *
   * @throws IllegalArgumentException naming the first malformed field, or if the stored state
   *     or attempt disagrees with the history
   */
  public Job job(JsonNode json) {
    Fields f = new Fields(json, "");
    List<StateChange> history = new ArrayList<>();
    for (Fields change : f.objects("history")) {
      history.add(change(change));
    }
    Optional<JobHandle> handle = f.optionalObject("handle").map(JsonCodec::handle);
    Job job = Job.restore(f.text("id"), spec(f.object("spec")), handle, history);
    if (!job.state().name().equals(f.text("state")) || job.attempt() != f.intValue("attempt")) {
      throw new IllegalArgumentException(
          "state/attempt " + f.text("state") + "/" + f.intValue("attempt")
              + " disagree with the history, which ends in " + job.state() + "/" + job.attempt());
    }
    return job;
  }

  private static ObjectNode specJson(MeasurementSpec spec) {
    ObjectNode node = NODES.objectNode();
    node.put("executor", spec.executor());
    ArrayNode command = node.putArray("harness_command");
    spec.harnessCommand().forEach(command::add);
    node.put("working_dir", spec.workingDir().toString());
    node.put("raw_dir", spec.rawDir().toString());
    node.set("checkpoint", checkpointJson(spec.checkpoint()));
    node.set("probe", probeJson(spec.probe()));
    return node;
  }

  private static MeasurementSpec spec(Fields f) {
    return MeasurementSpec.of(
        checkpoint(f.object("checkpoint")),
        probe(f.object("probe")),
        f.texts("harness_command"),
        f.path("working_dir"),
        f.path("raw_dir"),
        f.text("executor"));
  }

  private static ObjectNode checkpointJson(Checkpoint c) {
    ObjectNode node = NODES.objectNode();
    node.put("target", c.targetName());
    node.put("path", c.path().toString());
    node.put("step", c.step());
    node.put("fingerprint", c.fingerprint());
    node.put("type", c.type().wireName());
    putOptionalPath(node, "base_model", c.baseModel());
    putOptionalText(node, "base_model_fingerprint", c.baseModelFingerprint());
    node.put("final", c.isFinal());
    return node;
  }

  private static Checkpoint checkpoint(Fields f) {
    Checkpoint.Builder b =
        Checkpoint.builder()
            .targetName(f.text("target"))
            .path(f.path("path"))
            .step(f.longValue("step"))
            .fingerprint(f.text("fingerprint"))
            .type(wire(CheckpointType.class, f.text("type")))
            .isFinal(f.bool("final"));
    baseModel(b, f);
    return b.build();
  }

  private static ObjectNode probeJson(ResolvedProbe r) {
    Probe p = r.probe();
    ObjectNode node = NODES.objectNode();
    node.put("id", p.id());
    ObjectNode draft = node.putObject("draft");
    draft.put("id", p.draft().id());
    draft.put("path", p.draft().path().toString());
    draft.put("structure", p.draft().structure().wireName());
    draft.put("fingerprint", r.draftFingerprint());
    ObjectNode prompts = node.putObject("prompts");
    prompts.put("path", p.promptsPath().toString());
    prompts.put("sha256", r.promptSet().sha256());
    prompts.put("count", r.promptSet().promptCount());
    node.set("decoding", ProbeHasher.decodingJson(p.decoding()));
    node.put("estimator", p.estimator().wireName());
    ArrayNode seeds = node.putArray("seeds");
    p.seeds().forEach(seeds::add);
    node.put("hash", r.hash());
    return node;
  }

  private static ResolvedProbe probe(Fields f) {
    Fields draft = f.object("draft");
    Fields prompts = f.object("prompts");
    Probe probe =
        Probe.of(
            f.text("id"),
            Draft.of(
                draft.text("id"),
                draft.path("path"),
                wire(DraftStructure.class, draft.text("structure"))),
            prompts.path("path"),
            ReportJson.toDecoding(f.raw("decoding")),
            wire(Estimator.class, f.text("estimator")),
            f.ints("seeds"));
    PromptSet promptSet =
        PromptSet.of(prompts.path("path"), prompts.text("sha256"), prompts.intValue("count"));
    return ResolvedProbe.of(probe, draft.text("fingerprint"), promptSet, f.text("hash"));
  }

  private static ObjectNode handleJson(JobHandle h) {
    ObjectNode node = NODES.objectNode();
    node.put("executor", h.executor());
    node.put("native_id", h.nativeId());
    node.put("run_dir", h.runDir().toString());
    node.put("submitted_at", h.submittedAt().toString());
    putOptionalInstant(node, "native_start_time", h.nativeStartTime());
    return node;
  }

  private static JobHandle handle(Fields f) {
    return JobHandle.of(
        f.text("executor"),
        f.text("native_id"),
        f.path("run_dir"),
        f.instant("submitted_at"),
        f.optionalInstant("native_start_time"));
  }

  private static ObjectNode changeJson(StateChange c) {
    ObjectNode node = NODES.objectNode();
    if (c.from().isPresent()) {
      node.put("from", c.from().get().name());
    } else {
      node.putNull("from");
    }
    node.put("to", c.to().name());
    node.put("at", c.at().toString());
    node.put("attempt", c.attempt());
    node.put("cause", c.cause());
    if (c.failureReason().isPresent()) {
      node.put("failure_reason", c.failureReason().get().wireName());
    } else {
      node.putNull("failure_reason");
    }
    return node;
  }

  private static StateChange change(Fields f) {
    return StateChange.of(
        f.optionalText("from").map(JsonCodec::state),
        state(f.text("to")),
        f.instant("at"),
        f.intValue("attempt"),
        f.text("cause"),
        f.optionalText("failure_reason").map(r -> wire(FailureReason.class, r)));
  }

  private static JobState state(String name) {
    try {
      return JobState.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown job state '" + name + "'", e);
    }
  }

  // --- results -----------------------------------------------------------------------------

  /** A result file: flat provenance in SPEC.md F4 order, then the report as validated. */
  public ObjectNode measurementJson(Measurement m) {
    Provenance p = m.provenance();
    Checkpoint c = p.checkpoint();
    ObjectNode node = NODES.objectNode();
    ObjectNode prov = node.putObject("provenance");
    prov.put("target", c.targetName());
    prov.put("checkpoint_path", c.path().toString());
    prov.put("checkpoint_step", c.step());
    prov.put("checkpoint_fingerprint", c.fingerprint());
    prov.put("checkpoint_type", c.type().wireName());
    putOptionalPath(prov, "base_model", c.baseModel());
    putOptionalText(prov, "base_model_fingerprint", c.baseModelFingerprint());
    prov.put("checkpoint_final", c.isFinal());
    prov.put("probe_id", p.probeId());
    prov.put("probe_hash", p.probeHash());
    prov.put("draft_id", p.draftId());
    prov.put("draft_fingerprint", p.draftFingerprint());
    prov.put("harness_version", p.harnessVersion());
    prov.put("backend", p.backend());
    prov.put("dtype", p.dtype());
    prov.put("estimator", p.estimator().wireName());
    ArrayNode seeds = prov.putArray("seeds");
    p.seeds().forEach(seeds::add);
    prov.put("prompt_set_sha256", p.promptSetSha256());
    prov.put("executor", p.executor());
    prov.put("job_id", p.jobId());
    prov.put("attempt", p.attempt());
    prov.put("start_time", p.startTime().toString());
    prov.put("end_time", p.endTime().toString());
    prov.put("raw_report_path", p.rawReportPath().toString());
    node.set("report", ReportJson.toJson(m.report()));
    return node;
  }

  /**
   * Reads a result file.
   *
   * @throws IllegalArgumentException naming the first malformed field
   */
  public Measurement measurement(JsonNode json) {
    Fields f = new Fields(json, "");
    Fields p = f.object("provenance");
    Checkpoint.Builder c =
        Checkpoint.builder()
            .targetName(p.text("target"))
            .path(p.path("checkpoint_path"))
            .step(p.longValue("checkpoint_step"))
            .fingerprint(p.text("checkpoint_fingerprint"))
            .type(wire(CheckpointType.class, p.text("checkpoint_type")))
            .isFinal(p.bool("checkpoint_final"));
    baseModel(c, p);
    Provenance provenance =
        Provenance.builder()
            .checkpoint(c.build())
            .probeId(p.text("probe_id"))
            .probeHash(p.text("probe_hash"))
            .draftId(p.text("draft_id"))
            .draftFingerprint(p.text("draft_fingerprint"))
            .harnessVersion(p.text("harness_version"))
            .backend(p.text("backend"))
            .dtype(p.text("dtype"))
            .estimator(wire(Estimator.class, p.text("estimator")))
            .seeds(p.ints("seeds"))
            .promptSetSha256(p.text("prompt_set_sha256"))
            .executor(p.text("executor"))
            .jobId(p.text("job_id"))
            .attempt(p.intValue("attempt"))
            .startTime(p.instant("start_time"))
            .endTime(p.instant("end_time"))
            .rawReportPath(p.path("raw_report_path"))
            .build();
    JsonNode report = f.raw("report");
    try {
      ReportJson.checkShape(report);
    } catch (ReportViolationException e) {
      throw new IllegalArgumentException("report: " + e.getMessage(), e);
    }
    return Measurement.of(provenance, ReportJson.toReport(report));
  }

  // --- helpers -----------------------------------------------------------------------------

  private static <E extends Enum<E> & WireNamed> E wire(Class<E> type, String name) {
    return WireNamed.parse(type, name)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "'" + name + "' is not one of " + WireNamed.allNames(type)));
  }

  /** {@code base_model} and {@code base_model_fingerprint}: both present or both null. */
  private static void baseModel(Checkpoint.Builder b, Fields f) {
    Optional<Path> base = f.optionalPath("base_model");
    Optional<String> fingerprint = f.optionalText("base_model_fingerprint");
    if (base.isPresent() != fingerprint.isPresent()) {
      throw new IllegalArgumentException(
          "base_model and base_model_fingerprint must both be set or both be null");
    }
    base.ifPresent(path -> b.baseModel(path, fingerprint.get()));
  }

  private static void putOptionalText(ObjectNode node, String key, Optional<String> value) {
    if (value.isPresent()) {
      node.put(key, value.get());
    } else {
      node.putNull(key);
    }
  }

  private static void putOptionalPath(ObjectNode node, String key, Optional<Path> value) {
    if (value.isPresent()) {
      node.put(key, value.get().toString());
    } else {
      node.putNull(key);
    }
  }

  private static void putOptionalInstant(ObjectNode node, String key, Optional<Instant> value) {
    if (value.isPresent()) {
      node.put(key, value.get().toString());
    } else {
      node.putNull(key);
    }
  }
}
