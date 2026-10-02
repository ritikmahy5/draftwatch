package dev.draftwatch.harness;

import dev.draftwatch.config.CanonicalJson;
import dev.draftwatch.config.ProbeHasher;
import dev.draftwatch.domain.Checkpoint;
import dev.draftwatch.domain.CheckpointType;
import dev.draftwatch.domain.Probe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The exact command line that runs the harness (MEASUREMENT_CONTRACT.md, "Invocation"): the
 * configured harness command followed by the contract's arguments.
 *
 * <p>Built with a {@link Builder} because it has eight inputs and one cross-field rule:
 * {@code --base-model} is passed exactly for adapter checkpoints.
 */
public final class HarnessInvocation {
  private final List<String> harnessCommand;
  private final List<String> arguments;
  private final Path out;

  private HarnessInvocation(List<String> harnessCommand, List<String> arguments, Path out) {
    this.harnessCommand = harnessCommand;
    this.arguments = arguments;
    this.out = out;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The configured harness command followed by {@link #arguments()}. */
  public List<String> command() {
    List<String> command = new ArrayList<>(harnessCommand);
    command.addAll(arguments);
    return List.copyOf(command);
  }

  /** Only the contract's arguments, in the contract's order. */
  public List<String> arguments() {
    return arguments;
  }

  /** Where the harness must write its report. */
  public Path out() {
    return out;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HarnessInvocation)) {
      return false;
    }
    HarnessInvocation that = (HarnessInvocation) o;
    return harnessCommand.equals(that.harnessCommand) && arguments.equals(that.arguments);
  }

  @Override
  public int hashCode() {
    return Objects.hash(harnessCommand, arguments);
  }

  @Override
  public String toString() {
    return String.join(" ", command());
  }

  /** Builder for {@link HarnessInvocation}; {@link #build()} rejects missing inputs. */
  public static final class Builder {
    private List<String> harnessCommand;
    private Checkpoint checkpoint;
    private Probe probe;
    private Path out;

    private Builder() {}

    public Builder harnessCommand(List<String> harnessCommand) {
      this.harnessCommand = harnessCommand;
      return this;
    }

    /** Supplies {@code --target-checkpoint} and, for adapters, {@code --base-model}. */
    public Builder checkpoint(Checkpoint checkpoint) {
      this.checkpoint = checkpoint;
      return this;
    }

    /** Supplies the draft, prompts, decoding, estimator, and seeds arguments. */
    public Builder probe(Probe probe) {
      this.probe = probe;
      return this;
    }

    public Builder out(Path out) {
      this.out = out;
      return this;
    }

    /**
     * Builds the invocation.
     *
     * @throws NullPointerException naming the first missing input
     * @throws IllegalArgumentException if the harness command is empty, or an adapter
     *     checkpoint has no base model
     */
    public HarnessInvocation build() {
      Objects.requireNonNull(harnessCommand, "harness command must be set");
      Objects.requireNonNull(checkpoint, "checkpoint must be set");
      Objects.requireNonNull(probe, "probe must be set");
      Objects.requireNonNull(out, "out must be set");
      List<String> command = List.copyOf(harnessCommand);
      if (command.isEmpty()) {
        throw new IllegalArgumentException("harness command must not be empty");
      }
      List<String> args = new ArrayList<>();
      add(args, "--target-checkpoint", checkpoint.path().toString());
      Optional<Path> base = checkpoint.baseModel();
      if (checkpoint.type() == CheckpointType.ADAPTER) {
        add(args, "--base-model", base.orElseThrow(
            () -> new IllegalArgumentException("adapter checkpoint has no base model"))
            .toString());
      }
      add(args, "--draft-id", probe.draft().id());
      add(args, "--draft-path", probe.draft().path().toString());
      add(args, "--prompts", probe.promptsPath().toString());
      add(args, "--decoding-json", CanonicalJson.write(ProbeHasher.decodingJson(probe.decoding())));
      add(args, "--estimator", probe.estimator().wireName());
      StringJoiner seeds = new StringJoiner(",");
      for (int seed : probe.seeds()) {
        seeds.add(Integer.toString(seed));
      }
      add(args, "--seeds", seeds.toString());
      add(args, "--out", out.toString());
      return new HarnessInvocation(command, List.copyOf(args), out);
    }

    private static void add(List<String> args, String flag, String value) {
      args.add(flag);
      args.add(value);
    }
  }
}
