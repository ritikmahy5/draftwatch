package dev.draftwatch.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.draftwatch.config.RetrainSpec;
import dev.draftwatch.domain.Measurement;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.Provenance;
import dev.draftwatch.events.DetectionSubject;
import dev.draftwatch.events.RegressionDetected;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.JobHandle;
import dev.draftwatch.exec.JobIds;
import dev.draftwatch.exec.JobSpec;
import dev.draftwatch.store.AtomicFiles;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.store.RetrainRequest;
import dev.draftwatch.store.RetrainRequestRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code on_regression: [retrain_draft: { command: [...] }]}: submits the user's training command
 * through the configured Executor. At most one retrain is submitted per
 * target, draft id, and draft fingerprint. draftwatch never deploys the new draft.
 */
public final class RetrainDraftAction implements RegressionAction {
  /** The file in the run directory that holds the exact command. */
  public static final String INVOCATION_FILE = "invocation.json";

  private final String target;
  private final RetrainSpec spec;
  private final Executor executor;
  private final ResultRepository results;
  private final RetrainRequestRepository requests;
  private final Function<String, Optional<Probe>> probes;
  private final JobIds ids;
  private final Path stateDir;
  private final Path workingDir;
  private final Consumer<String> console;
  private final ObjectMapper json = new ObjectMapper();

  /**
   * @param target the target whose regressions this action handles
   * @param probes the configured probe of an id, for the draft's current path
   * @param workingDir the training command's working directory: the config file's directory
   * @param console receives one line per regression handled
   */
  public RetrainDraftAction(
      String target,
      RetrainSpec spec,
      Executor executor,
      ResultRepository results,
      RetrainRequestRepository requests,
      Function<String, Optional<Probe>> probes,
      JobIds ids,
      Path stateDir,
      Path workingDir,
      Consumer<String> console) {
    this.target = Objects.requireNonNull(target, "target");
    this.spec = Objects.requireNonNull(spec, "spec");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.results = Objects.requireNonNull(results, "results");
    this.requests = Objects.requireNonNull(requests, "requests");
    this.probes = Objects.requireNonNull(probes, "probes");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
    this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
    this.console = Objects.requireNonNull(console, "console");
  }

  @Override
  public String name() {
    return "retrain_draft";
  }

  /**
   * Submits the training job, unless this draft was already sent for retraining.
   *
   * @throws ActionFailedException if the regressed result cannot be found or the executor
   *     refuses the job; nothing is recorded then, so the next regression tries again
   */
  @Override
  public void execute(RegressionDetected event) {
    DetectionSubject s = event.subject();
    if (!s.target().equals(target)) {
      return;
    }
    Measurement m =
        results.find(s.fingerprint(), s.probeHash()).stream()
            .filter(x -> x.jobId().equals(s.jobId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ActionFailedException(
                        "retrain_draft: no stored result for job " + s.jobId()));
    Provenance p = m.provenance();
    Optional<RetrainRequest> earlier =
        requests.find(p.targetName(), p.draftId(), p.draftFingerprint());
    if (earlier.isPresent()) {
      console.accept(
          "retrain_draft: draft " + p.draftId() + " (" + p.draftFingerprint() + ") of target "
              + p.targetName() + " was already sent for retraining as "
              + earlier.get().retrainId() + " after job " + earlier.get().triggeredByJob()
              + "; not submitting again");
      return;
    }
    String id = "retrain-" + ids.next();
    Path runDir = stateDir.resolve("retrain").resolve(id);
    JobSpec job =
        JobSpec.builder()
            .jobId(id)
            .attempt(1)
            .command(command(p, s, runDir))
            .workingDir(workingDir)
            .runDir(runDir)
            .build();
    writeInvocation(job);
    JobHandle handle;
    try {
      handle = executor.submit(job);
    } catch (ExecutorException e) {
      throw new ActionFailedException(
          "retrain_draft: the executor refused the training job " + id + ": " + e.getMessage());
    }
    requests.record(
        RetrainRequest.of(
            id,
            p.targetName(),
            p.probeId(),
            p.draftId(),
            p.draftFingerprint(),
            m.jobId(),
            p.checkpoint().step(),
            s.resultFile(),
            handle));
    console.accept(
        "retrain_draft: submitted training job " + id + " as " + handle + " for draft "
            + p.draftId() + " after the regression of job " + m.jobId() + "; its output is in "
            + runDir + ". draftwatch does not deploy the new draft: point the probe's"
            + " draft.path at it when it is ready");
  }

  /** {@code env DRAFTWATCH_...=... <command>}: the regression's context, never templated. */
  List<String> command(Provenance p, DetectionSubject s, Path runDir) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("DRAFTWATCH_TARGET", p.targetName());
    env.put("DRAFTWATCH_PROBE", p.probeId());
    env.put("DRAFTWATCH_CHECKPOINT", p.checkpoint().path().toString());
    env.put("DRAFTWATCH_CHECKPOINT_STEP", Long.toString(p.checkpoint().step()));
    env.put("DRAFTWATCH_CHECKPOINT_FINGERPRINT", p.checkpoint().fingerprint());
    env.put("DRAFTWATCH_DRAFT_ID", p.draftId());
    probes
        .apply(p.probeId())
        .ifPresent(probe -> env.put("DRAFTWATCH_DRAFT_PATH", probe.draft().path().toString()));
    env.put("DRAFTWATCH_DRAFT_FINGERPRINT", p.draftFingerprint());
    env.put("DRAFTWATCH_RESULT_FILE", s.resultFile().toString());
    env.put("DRAFTWATCH_RETRAIN_DIR", runDir.toString());
    List<String> command = new ArrayList<>();
    command.add("env");
    env.forEach((k, v) -> command.add(k + "=" + v));
    command.addAll(spec.command());
    return command;
  }

  private void writeInvocation(JobSpec job) {
    Path file = job.runDir().resolve(INVOCATION_FILE);
    ArrayNode argv = json.createArrayNode();
    job.command().forEach(argv::add);
    try {
      Files.createDirectories(job.runDir());
      AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(argv));
    } catch (IOException e) {
      throw new ActionFailedException("retrain_draft: cannot write " + file + ": " + e);
    }
  }
}
