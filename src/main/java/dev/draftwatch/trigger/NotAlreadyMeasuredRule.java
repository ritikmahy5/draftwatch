package dev.draftwatch.trigger;

import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.exec.Job;
import java.util.List;

/**
 * {@code not_already_measured}: Reject if a result exists for (fingerprint, probe hash); else
 * Abstain (SPEC.md F2). It also rejects when any job for that pair exists, in any state
 * (DECISIONS.md D52): a running job would otherwise be submitted again on every pass, and a job
 * that failed for a reason a retry cannot fix would be resubmitted forever. {@code draftwatch
 * submit} still measures again on request.
 */
public final class NotAlreadyMeasuredRule implements TriggerRule {
  @Override
  public String describe() {
    return "not_already_measured";
  }

  @Override
  public TriggerDecision evaluate(Checkpoint checkpoint, ResolvedProbe probe, History history) {
    if (history.hasResult(checkpoint.fingerprint(), probe.hash())) {
      return TriggerDecision.reject("already measured");
    }
    List<Job> jobs = history.jobs(checkpoint.fingerprint(), probe.hash());
    if (!jobs.isEmpty()) {
      Job last = jobs.get(jobs.size() - 1);
      return TriggerDecision.reject(
          "job " + last.id() + " is " + last.state()
              + last.failureReason().map(r -> " (" + r.wireName() + ")").orElse("")
              + "; 'draftwatch submit' measures it again on request");
    }
    return TriggerDecision.abstain();
  }
}
