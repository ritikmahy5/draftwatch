package dev.draftwatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.config.CompletionSpec;
import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.DraftwatchConfig;
import dev.draftwatch.config.ExecutorConfig;
import dev.draftwatch.discovery.CheckpointInspector;
import dev.draftwatch.discovery.CompletionPolicy;
import dev.draftwatch.discovery.MarkerCompletionPolicy;
import dev.draftwatch.discovery.SettleCompletionPolicy;
import dev.draftwatch.exec.Executor;
import dev.draftwatch.exec.JobPoller;
import dev.draftwatch.exec.LocalExecutor;
import dev.draftwatch.exec.RetryPolicy;
import dev.draftwatch.exec.TimestampJobIds;
import dev.draftwatch.fingerprint.FingerprintMethod;
import dev.draftwatch.fingerprint.Fingerprinter;
import dev.draftwatch.fingerprint.FullFileFingerprinter;
import dev.draftwatch.fingerprint.SampledBlockFingerprinter;
import dev.draftwatch.harness.ProbeResolver;
import dev.draftwatch.harness.PromptSetReader;
import dev.draftwatch.harness.ReportParser;
import dev.draftwatch.stats.MetricCalculator;
import dev.draftwatch.store.FileJobRepository;
import dev.draftwatch.store.FileResultRepository;
import dev.draftwatch.store.JobRepository;
import dev.draftwatch.store.JsonCodec;
import dev.draftwatch.store.ResultRepository;
import dev.draftwatch.store.StateLock;
import dev.draftwatch.store.SystemHostIdentity;
import dev.draftwatch.store.SystemProcessTable;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Factory that wires every object in the application; it is the only place where concrete
 * classes are chosen and config type names become objects, so the rest of the code depends on
 * interfaces.
 */
public final class Bootstrap {
  /** The command set from SPEC.md, section "CLI", in the order shown there. */
  static final List<CommandUsage> COMMANDS =
      List.of(
          CommandUsage.of("init", "", "create draftwatch.yaml template + state directory"),
          CommandUsage.of(
              "validate", "", "validate config, print resolved probes and trigger chains"),
          CommandUsage.of(
              "watch", "[--once]", "discover -> trigger -> submit -> poll -> detect"),
          CommandUsage.of(
              "submit", "<target> <ckpt>", "queue measurements for one checkpoint"),
          CommandUsage.of(
              "schedule", "[--interval 15m]", "Slurm only: self-resubmitting watch --once job"),
          CommandUsage.of("unschedule", "", "cancel the scheduled watch job"),
          CommandUsage.of("status", "", "non-terminal and recently failed jobs"),
          CommandUsage.of("history", "<target> --probe <id>", "results in step order"),
          CommandUsage.of(
              "diff",
              "<ckptA> <ckptB> --probe <id>",
              "compare two measurements with provenance"),
          CommandUsage.of("report", "[--out report.html]", "static HTML report"),
          CommandUsage.of(
              "baseline", "<target> [<ckpt>]", "set or show the baseline checkpoint"));

  private final PrintStream out;
  private final PrintStream err;
  private final Map<String, String> environment;
  private final Clock clock;
  private final Sleeper sleeper;

  public Bootstrap(PrintStream out, PrintStream err) {
    this(out, err, System.getenv(), Clock.systemUTC(), Sleeper.system());
  }

  Bootstrap(
      PrintStream out,
      PrintStream err,
      Map<String, String> environment,
      Clock clock,
      Sleeper sleeper) {
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.environment = Map.copyOf(environment);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public Cli cli() {
    ConfigLoader loader = new ConfigLoader(new ConfigValidator());
    Map<String, CliCommand> commands =
        Map.of(
            "init", new InitCommand(),
            "validate", new ValidateCommand(loader, this::services),
            "submit", new SubmitCommand(loader, this::services),
            "status", new StatusCommand(loader, this::services),
            "history", new HistoryCommand(loader, this::services));
    return new Cli(COMMANDS, commands, out, err);
  }

  /** Everything that depends on a loaded config. */
  Services services(DraftwatchConfig config) {
    ObjectMapper json = new ObjectMapper();
    Fingerprinter fingerprinter = fingerprinter(config.fingerprintMethod());
    JsonCodec codec = new JsonCodec();
    JobRepository jobs = new FileJobRepository(config.stateDir(), codec, json);
    ResultRepository results = new FileResultRepository(config.stateDir(), codec, json);
    return new Services(
        config,
        new ProbeResolver(fingerprinter, new PromptSetReader(json)),
        new CheckpointInspector(fingerprinter, json, clock),
        Bootstrap::completionPolicy,
        jobs,
        results,
        new StateLock(new SystemHostIdentity(environment), new SystemProcessTable(), clock),
        () -> runner(config, jobs, results),
        sleeper,
        clock);
  }

  private MeasurementRunner runner(
      DraftwatchConfig config, JobRepository jobs, ResultRepository results) {
    Executor executor = executor(config.executor());
    return new MeasurementRunner(
        executor,
        new JobPoller(executor, new ReportParser(new MetricCalculator()), clock),
        new RetryPolicy(config.executor().maxRetries()),
        jobs,
        results,
        new TimestampJobIds(clock, new SecureRandom()),
        clock,
        config.stateDir(),
        config.harness().command(),
        config.baseDir());
  }

  /**
   * The executor for {@code executor.type}.
   *
   * @throws UnsupportedOperationException for {@code slurm}, which arrives in M5
   */
  private Executor executor(ExecutorConfig config) {
    switch (config.type()) {
      case LOCAL:
        return new LocalExecutor(clock);
      case SLURM:
        throw new UnsupportedOperationException(
            "executor.type slurm is not available until M5 (docs/ROADMAP.md); use"
                + " executor.type: local");
      default:
        throw new IllegalArgumentException("unknown executor type " + config.type());
    }
  }

  /** The fingerprinter for {@code fingerprint: <method>}. */
  static Fingerprinter fingerprinter(FingerprintMethod method) {
    switch (method) {
      case SAMPLED:
        return new SampledBlockFingerprinter();
      case FULL:
        return new FullFileFingerprinter();
      default:
        throw new IllegalArgumentException("unknown fingerprint method " + method);
    }
  }

  /** The completion policy for a target's {@code completion}. */
  static CompletionPolicy completionPolicy(CompletionSpec spec) {
    switch (spec.kind()) {
      case MARKER:
        return new MarkerCompletionPolicy(spec.marker());
      case SETTLE_SECONDS:
        return new SettleCompletionPolicy(Duration.ofSeconds(spec.settleSecondsValue()));
      default:
        throw new IllegalArgumentException("unknown completion policy " + spec.kind());
    }
  }
}
