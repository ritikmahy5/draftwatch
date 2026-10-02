package dev.draftwatch.harness;

import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.Probe;
import dev.draftwatch.domain.PromptSet;
import dev.draftwatch.domain.ResolvedProbe;
import dev.draftwatch.fingerprint.Fingerprinter;
import java.util.Objects;

/**
 * Reads the files a probe's identity depends on (the draft's weights and the prompt file) and
 * computes its probe hash.
 */
public final class ProbeResolver {
  private final Fingerprinter fingerprinter;
  private final PromptSetReader promptSetReader;

  public ProbeResolver(Fingerprinter fingerprinter, PromptSetReader promptSetReader) {
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
    this.promptSetReader = Objects.requireNonNull(promptSetReader, "promptSetReader");
  }

  /**
   * Resolves {@code probe} against the files as they are now.
   *
   * @throws dev.draftwatch.fingerprint.FingerprintException if the draft cannot be fingerprinted
   * @throws PromptSetException if the prompt file is unreadable or invalid
   */
  public ResolvedProbe resolve(Probe probe) {
    String draftFingerprint = fingerprinter.fingerprint(probe.draft().path());
    PromptSet promptSet = promptSetReader.read(probe.promptsPath());
    String hash = ProbeHasher.hash(probe, draftFingerprint, promptSet.sha256());
    return ResolvedProbe.of(probe, draftFingerprint, promptSet, hash);
  }
}
