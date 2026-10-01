package dev.draftwatch.config;

import dev.draftwatch.domain.Probe;
import dev.draftwatch.fingerprint.FingerprintMethod;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated {@code draftwatch.yaml}. Every path is absolute and normalized; relative paths in
 * the file were resolved against {@link #baseDir()} (DECISIONS.md D21).
 */
public final class DraftwatchConfig {
  /** D3: the state directory used when {@code state_dir} is omitted. */
  public static final String DEFAULT_STATE_DIR = ".draftwatch";

  private final Path configFile;
  private final Path stateDir;
  private final FingerprintMethod fingerprintMethod;
  private final ExecutorConfig executor;
  private final HarnessConfig harness;
  private final List<Probe> probes;
  private final List<TargetConfig> targets;

  private DraftwatchConfig(
      Path configFile,
      Path stateDir,
      FingerprintMethod fingerprintMethod,
      ExecutorConfig executor,
      HarnessConfig harness,
      List<Probe> probes,
      List<TargetConfig> targets) {
    this.configFile = configFile;
    this.stateDir = stateDir;
    this.fingerprintMethod = fingerprintMethod;
    this.executor = executor;
    this.harness = harness;
    this.probes = probes;
    this.targets = targets;
  }

  /** Assembles a config; {@link ConfigValidator} is the intended caller. */
  public static DraftwatchConfig of(
      Path configFile,
      Path stateDir,
      FingerprintMethod fingerprintMethod,
      ExecutorConfig executor,
      HarnessConfig harness,
      List<Probe> probes,
      List<TargetConfig> targets) {
    return new DraftwatchConfig(
        Objects.requireNonNull(configFile, "configFile"),
        Objects.requireNonNull(stateDir, "stateDir"),
        Objects.requireNonNull(fingerprintMethod, "fingerprintMethod"),
        Objects.requireNonNull(executor, "executor"),
        Objects.requireNonNull(harness, "harness"),
        List.copyOf(probes),
        List.copyOf(targets));
  }

  /** Absolute path of the file this config was read from. */
  public Path configFile() {
    return configFile;
  }

  /** The config file's directory; relative paths in the file are relative to it. */
  public Path baseDir() {
    return configFile.getParent();
  }

  public Path stateDir() {
    return stateDir;
  }

  /** {@code fingerprint}; applies to checkpoints and drafts alike (DECISIONS.md D23). */
  public FingerprintMethod fingerprintMethod() {
    return fingerprintMethod;
  }

  public ExecutorConfig executor() {
    return executor;
  }

  public HarnessConfig harness() {
    return harness;
  }

  public List<Probe> probes() {
    return probes;
  }

  public Optional<Probe> probe(String id) {
    return probes.stream().filter(p -> p.id().equals(id)).findFirst();
  }

  public List<TargetConfig> targets() {
    return targets;
  }

  public Optional<TargetConfig> target(String name) {
    return targets.stream().filter(t -> t.name().equals(name)).findFirst();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DraftwatchConfig)) {
      return false;
    }
    DraftwatchConfig that = (DraftwatchConfig) o;
    return configFile.equals(that.configFile)
        && stateDir.equals(that.stateDir)
        && fingerprintMethod == that.fingerprintMethod
        && executor.equals(that.executor)
        && harness.equals(that.harness)
        && probes.equals(that.probes)
        && targets.equals(that.targets);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        configFile, stateDir, fingerprintMethod, executor, harness, probes, targets);
  }
}
