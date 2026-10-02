# Class diagram

Updated at the end of every milestone (DECISIONS.md D1). Shows the classes that exist in
`src/main/java` now, not the planned design; for the planned design see `ARCHITECTURE.md`.
Accessors that only return a field are omitted; every domain and config class is immutable
(private final fields, static factory or builder, no setters).

**As of:** M5: Slurm executor (code complete; the recordings from Explorer are pending).

## app: entry point, CLI commands, orchestration

```mermaid
classDiagram
  direction LR

  class Main {
    <<final>>
    +main(String[] args)$ void
  }
  class Bootstrap {
    <<final, Factory>>
    ~COMMANDS$ List~CommandUsage~
    +cli() Cli
    ~services(DraftwatchConfig) Services
    ~fingerprinter(FingerprintMethod)$ Fingerprinter
    ~completionPolicy(CompletionSpec)$ CompletionPolicy
  }
  class Cli {
    <<final>>
    +EXIT_OK$ int
    +EXIT_FAILURE$ int
    +EXIT_USAGE$ int
    +run(List~String~ args) int
    +usage() String
  }
  class CliCommand {
    <<interface, Command>>
    +run(CommandContext context, List~String~ args) int
  }
  class CommandContext {
    <<final>>
    -configFile Path
    +fail(String message) int
    +requireNoArguments(String, List~String~) int
  }
  class CommandUsage {
    <<final>>
    +synopsis() String
  }
  class InitCommand { <<final>> }
  class BaselineCommand { <<final>> }
  class WatchCommand {
    <<final>>
    ~DEFAULT_INTERVAL$ Duration
  }
  class WatchService {
    <<final>>
    +pass() PassReport
  }
  class PassReport {
    <<final>>
    +submitted() List~Submitted~
    +baselineSubmitted() List~Job~
    +skipped() List~Skipped~
    +notMeasuredByRule() Map~String, Integer~
    +finished() List~Job~
    +stillActive() int
  }
  class DetectionService {
    <<final>>
    +onMeasurementStored(MeasurementStored) void
  }
  class ConsoleDetectionPrinter {
    <<final>>
    +onDetection(DetectionEvent) void
    +onBaselinePinned(BaselinePinned) void
  }
  class ValidateCommand { <<final>> }
  class SubmitCommand { <<final>> }
  class StatusCommand { <<final>> }
  class HistoryCommand { <<final>> }
  class Services {
    <<final>>
    +probeResolver() ProbeResolver
    +inspector() CheckpointInspector
    +completionPolicy(CompletionSpec) CompletionPolicy
    +jobs() JobRepository
    +results() ResultRepository
    +stateLock() StateLock
    +runner() MeasurementRunner
    +baselines() BaselineRepository
    +detections() DetectionLog
    +bus() EventBus
  }
  class MeasurementRunner {
    <<final>>
    +create(Checkpoint, ResolvedProbe) Job
    +submit(Job) Job
    +advance(Job) Job
    +isDone(Job) boolean
    +runToCompletion(List~Job~, Sleeper) List~Job~
    +result(Job) Optional~Measurement~
  }
  class Sleeper {
    <<interface>>
    +sleep(Duration) void
  }
  class ScheduleCommand { <<final>> }
  class UnscheduleCommand { <<final>> }
  class Schedule {
    <<final>>
    -dir Path
    -jobName String
    +options() List~String~
    +resubmitOptions(Duration) List~String~
    +script(String token, Duration, Instant) String
    +watchCommand() List~String~
  }

  Main ..> Bootstrap : creates
  Bootstrap ..> Cli : creates
  Bootstrap ..> Services : creates per config
  Cli o-- "*" CommandUsage
  Cli o-- "*" CliCommand
  Cli ..> CommandContext : creates
  CliCommand <|.. InitCommand
  CliCommand <|.. ValidateCommand
  CliCommand <|.. SubmitCommand
  CliCommand <|.. StatusCommand
  CliCommand <|.. HistoryCommand
  CliCommand <|.. BaselineCommand
  CliCommand <|.. WatchCommand
  CliCommand <|.. ScheduleCommand
  CliCommand <|.. UnscheduleCommand
  ScheduleCommand ..> Schedule : writes and submits
  UnscheduleCommand ..> Schedule : ends
  ScheduleCommand --> SlurmCli
  UnscheduleCommand --> SlurmCli
  WatchCommand ..> WatchService : one pass per lock
  WatchService ..> PassReport : returns
  WatchService --> CheckpointSource
  WatchService --> TriggerChain
  WatchService --> History
  WatchService --> MeasurementRunner
  Bootstrap ..> DetectionService : subscribes
  Bootstrap ..> ConsoleDetectionPrinter : subscribes
  DetectionService --> DetectorSuite
  DetectionService --> BaselineRepository
  DetectionService --> DetectionLog
  DetectionService --> EventBus
  MeasurementRunner --> EventBus : publishes MeasurementStored
  ValidateCommand ..> Services
  SubmitCommand ..> Services
  StatusCommand ..> Services
  HistoryCommand ..> Services
  Services o-- MeasurementRunner
  MeasurementRunner --> Executor
  MeasurementRunner --> JobPoller
  MeasurementRunner --> RetryPolicy
  MeasurementRunner --> JobRepository
  MeasurementRunner --> ResultRepository
  MeasurementRunner --> JobIds
  SubmitCommand ..> Sleeper
```

## config: YAML to validated config, canonical hashing

```mermaid
classDiagram
  direction TB

  class ConfigLoader {
    <<final>>
    +DEFAULT_FILE_NAME$ String
    -yaml ObjectMapper
    +load(Path file) DraftwatchConfig
  }
  class ConfigValidator {
    <<final>>
    +validate(JsonNode document, Path configFile) DraftwatchConfig
  }
  class ConfigNode {
    <<final, package-private>>
    -node JsonNode
    -path String
    +required(String key) ConfigNode
    +optional(String key) ConfigNode
    +mapping(String... allowedKeys) boolean
    +elements() List~ConfigNode~
    +asString() String
    +asInt(int min) Integer
    +asDecimal() BigDecimal
    +asEnum(Class~E~ type) E
    +asPath(Path baseDir) Path
  }
  class ConfigError {
    <<final>>
    -field String
    -message String
  }
  class ConfigException {
    <<final>>
    -file Path
    -errors List~ConfigError~
  }
  class DraftwatchConfig {
    <<final>>
    -configFile Path
    -stateDir Path
    -fingerprintMethod FingerprintMethod
    +probe(String id) Optional~Probe~
    +target(String name) Optional~TargetConfig~
  }
  class ExecutorConfig {
    <<final>>
    -type ExecutorType
    -maxRetries int
    -slurm Optional~SlurmConfig~
  }
  class SlurmConfig {
    <<final>>
    -partition Optional~String~
    -gres Optional~String~
    -time Optional~String~
    -requeueOnPreempt boolean
    -extraSbatchArgs List~String~
    -scheduleSbatchArgs List~String~
  }
  class SbatchOptions {
    <<final>>
    +GPU_VARIABLES$ Set~String~
    ~refuseForMeasurement(String)$ Optional~String~
    ~refuseForSchedule(String)$ Optional~String~
  }
  class HarnessConfig {
    <<final>>
    -command List~String~
  }
  class TargetConfig {
    <<final>>
    -target Target
    -completion CompletionSpec
    -probeIds List~String~
    -triggers List~TriggerSpec~
    -detectors List~DetectorSpec~
    -onRegression List~ActionKind~
  }
  class CompletionSpec {
    <<final>>
    -kind Kind
    +marker(String)$ CompletionSpec
    +settleSeconds(int)$ CompletionSpec
  }
  class TriggerSpec {
    <<final>>
    -kind Kind
    -argument OptionalInt
    +defaultChain()$ List~TriggerSpec~
  }
  class DetectorSpec {
    <<interface>>
    +kind() Kind
    +metric() Metric
    +describe() String
  }
  class PairedBootstrapSpec {
    <<final>>
    -confidence double
    -minEffect double
    -resamples int
    -bootstrapSeed long
  }
  class AbsoluteDropSpec {
    <<final>>
    -maxDrop double
  }
  class NoiseFloorSpec {
    <<final>>
    -k double
    -sigma double
  }
  class TrendSpec {
    <<final>>
    -window int
    -maxSlope double
  }
  class ExecutorType {
    <<enumeration>>
    LOCAL
    SLURM
  }
  class ActionKind {
    <<enumeration>>
    NOTIFY
  }
  class CanonicalJson {
    <<final>>
    +write(JsonNode)$ String
    +bytes(JsonNode)$ byte[]
    +canonicalDecimal(BigDecimal)$ String
  }
  class ProbeHasher {
    <<final>>
    +hash(Probe, String draftFingerprint, String promptSetSha256)$ String
    +canonicalInput(Probe, String, String)$ String
    +decodingJson(Decoding)$ ObjectNode
  }

  ConfigLoader --> ConfigValidator
  ConfigValidator ..> ConfigNode : walks with
  ConfigValidator ..> ConfigException : throws
  ConfigNode ..> ConfigError : records
  ConfigException o-- "1..*" ConfigError
  ConfigValidator ..> DraftwatchConfig : builds
  DraftwatchConfig *-- ExecutorConfig
  DraftwatchConfig *-- HarnessConfig
  DraftwatchConfig *-- "1..*" Probe
  DraftwatchConfig *-- "1..*" TargetConfig
  ExecutorConfig *-- "0..1" SlurmConfig
  ConfigValidator ..> SbatchOptions : checks sbatch args
  ExecutorConfig --> ExecutorType
  TargetConfig *-- Target
  TargetConfig *-- CompletionSpec
  TargetConfig *-- "1..*" TriggerSpec
  TargetConfig *-- "1..*" DetectorSpec
  TargetConfig --> "*" ActionKind
  DetectorSpec <|.. PairedBootstrapSpec
  DetectorSpec <|.. AbsoluteDropSpec
  DetectorSpec <|.. NoiseFloorSpec
  DetectorSpec <|.. TrendSpec
  ProbeHasher ..> CanonicalJson : uses
```

## domain: immutable domain classes

```mermaid
classDiagram
  direction TB

  class Target {
    <<final>>
    -name String
    -checkpointDirs List~Path~
    -checkpointType CheckpointType
    -baseModel Optional~Path~
    -stepRegex Pattern
    -finalMarker String
    +builder()$ Builder
  }
  class Checkpoint {
    <<final>>
    -targetName String
    -path Path
    -step long
    -fingerprint String
    -type CheckpointType
    -baseModel Optional~Path~
    -baseModelFingerprint Optional~String~
    -isFinal boolean
    +builder()$ Builder
  }
  class Probe {
    <<final>>
    -id String
    -promptsPath Path
    -estimator Estimator
    -seeds List~Integer~
    +of(...)$ Probe
  }
  class Draft {
    <<final>>
    -id String
    -path Path
    -structure DraftStructure
  }
  class Decoding {
    <<final>>
    -temperature BigDecimal
    -maxNewTokens int
    -numSpeculativeTokens int
    -dtype String
    +isGreedy() boolean
  }
  class PromptSet {
    <<final>>
    -path Path
    -sha256 String
    -promptCount int
  }
  class ResolvedProbe {
    <<final>>
    -draftFingerprint String
    -hash String
  }
  class Measurement {
    <<final>>
    +of(Provenance, AcceptanceReport)$ Measurement
    +fingerprint() String
    +probeHash() String
    +jobId() String
  }
  class Provenance {
    <<final>>
    -probeId String
    -probeHash String
    -draftId String
    -draftFingerprint String
    -harnessVersion String
    -backend String
    -dtype String
    -estimator Estimator
    -seeds List~Integer~
    -promptSetSha256 String
    -executor String
    -jobId String
    -attempt int
    -startTime Instant
    -endTime Instant
    -rawReportPath Path
    +builder()$ Builder
  }
  class AcceptanceReport {
    <<final>>
    -schemaVersion int
    -harnessVersion String
    -backend String
    -adapterHandling AdapterHandling
    -draftStructure DraftStructure
    -estimator Estimator
    -draftId String
    -promptSetSha256 String
    -numPrompts int
    -wallClockSeconds double
    +builder()$ Builder
  }
  class SeedReport {
    <<final>>
    -seed int
    -alpha double
    -tau double
    -alphaByPosition List~OptionalDouble~
    -totalSteps long
    -totalProposed long
    -totalAccepted long
    -excludedPrompts int
    -positionCountsExact boolean
  }
  class PromptCounts {
    <<final>>
    -promptIndex int
    -steps long
    -proposed long
    -accepted long
  }
  class PositionCount {
    <<final>>
    -position int
    -eligible long
    -accepted long
  }
  class AggregateMetrics {
    <<final>>
    -alphaMean double
    -alphaStd OptionalDouble
    -tauMean double
    -tauStd OptionalDouble
  }
  class Hardware {
    <<final>>
    -gpu String
    -count int
  }
  class WireNamed {
    <<interface>>
    +wireName() String
    +parse(Class~E~, String)$ Optional~E~
    +allNames(Class~E~)$ String
  }
  class Estimator {
    <<enumeration>>
    TOKEN_WEIGHTED
    SIMPLE_MEAN
  }
  class CheckpointType {
    <<enumeration>>
    FULL
    ADAPTER
  }
  class DraftStructure {
    <<enumeration>>
    CHAIN
  }
  class AdapterHandling {
    <<enumeration>>
    NONE
    MERGED
  }
  class Metric {
    <<enumeration>>
    ALPHA
    TAU
  }
  class Names {
    <<final>>
    +isValid(String)$ boolean
  }
  class Baseline {
    <<final>>
    -targetName String
    -fingerprint String
    -path Path
    -step long
    -setAt Instant
    -source Source
  }

  Probe *-- Draft
  Probe *-- Decoding
  ResolvedProbe *-- Probe
  ResolvedProbe *-- PromptSet
  Measurement *-- Provenance
  Measurement *-- AcceptanceReport
  Provenance *-- Checkpoint
  AcceptanceReport *-- Decoding
  AcceptanceReport *-- "1..*" SeedReport
  AcceptanceReport *-- AggregateMetrics
  AcceptanceReport *-- Hardware
  SeedReport *-- "*" PromptCounts
  SeedReport *-- "*" PositionCount
  WireNamed <|.. Estimator
  WireNamed <|.. CheckpointType
  WireNamed <|.. DraftStructure
  WireNamed <|.. AdapterHandling
  WireNamed <|.. Metric
```

## fingerprint: content fingerprints

```mermaid
classDiagram
  direction LR

  class Fingerprinter {
    <<interface, Strategy>>
    +fingerprint(Path dir) String
  }
  class WeightFileFingerprinter {
    <<abstract, Template Method>>
    -method FingerprintMethod
    +fingerprint(Path dir) String
    #contentDigest(Path file, long size)* byte[]
    #digestOf(Path, long, RangeReader)$ byte[]
  }
  class SampledBlockFingerprinter {
    <<final>>
    +BLOCK_BYTES$ int
    +BLOCK_COUNT$ int
    ~blockOffset(int i, long size) long
  }
  class FullFileFingerprinter { <<final>> }
  class FingerprintMethod {
    <<enumeration>>
    SAMPLED
    FULL
  }
  class FingerprintException { <<final>> }
  class Sha256 {
    <<final>>
    +newDigest()$ MessageDigest
    +hex(byte[])$ String
  }
  class AdapterFingerprint {
    <<final>>
    +combine(String adapter, String base)$ String
  }
  class CachingFingerprinter {
    <<final, Decorator>>
    -delegate Fingerprinter
    -method FingerprintMethod
    -cache FingerprintCache
    ~signature(Path dir)$ String
  }
  class FingerprintCache {
    <<interface>>
    +get(String key) Optional~Entry~
    +put(String key, Entry entry) void
  }
  class WeightFiles {
    <<final, package-private>>
    ~under(Path dir)$ TreeMap~String, Path~
  }

  Fingerprinter <|.. CachingFingerprinter
  CachingFingerprinter --> Fingerprinter : delegate
  CachingFingerprinter --> FingerprintCache
  CachingFingerprinter ..> WeightFiles : signature
  WeightFileFingerprinter ..> WeightFiles : lists

  AdapterFingerprint ..> Sha256 : uses
  Fingerprinter <|.. WeightFileFingerprinter
  WeightFileFingerprinter <|-- SampledBlockFingerprinter
  WeightFileFingerprinter <|-- FullFileFingerprinter
  WeightFileFingerprinter --> FingerprintMethod
  WeightFileFingerprinter ..> Sha256 : uses
  WeightFileFingerprinter ..> FingerprintException : throws
```

## discovery: checkpoint directories

```mermaid
classDiagram
  direction LR

  class CheckpointInspector {
    <<final>>
    -fingerprinter Fingerprinter
    +inspect(Target, Path dir, CompletionPolicy) Checkpoint
  }
  class CompletionPolicy {
    <<interface, Strategy>>
    +incompleteReason(Path dir, Instant now) Optional~String~
  }
  class MarkerCompletionPolicy {
    <<final>>
    -marker String
  }
  class SettleCompletionPolicy {
    <<final>>
    -settle Duration
  }
  class StepExtractor {
    <<final>>
    +TRAINER_STATE_FILE$ String
    -stepRegex Pattern
    +extract(Path checkpointDir) long
  }
  class CheckpointRejectedException {
    <<final>>
    +incomplete(Path, String)$ CheckpointRejectedException
    +isIncomplete() boolean
  }
  class CheckpointSource {
    <<interface, Strategy>>
    +poll() Discovery
  }
  class DirectoryCheckpointSource {
    <<final>>
    -target Target
  }
  class Discovery {
    <<final>>
    +checkpoints() List~Checkpoint~
    +skipped() List~Skipped~
  }

  CheckpointSource <|.. DirectoryCheckpointSource
  DirectoryCheckpointSource --> CheckpointInspector
  DirectoryCheckpointSource ..> Discovery : returns
  class StepExtractionException { <<final>> }

  CompletionPolicy <|.. MarkerCompletionPolicy
  CompletionPolicy <|.. SettleCompletionPolicy
  CheckpointInspector ..> CompletionPolicy : uses
  CheckpointInspector ..> AdapterFingerprint : adapters
  CheckpointInspector ..> StepExtractor : uses
  CheckpointInspector --> Fingerprinter
  CheckpointInspector ..> CheckpointRejectedException : throws
  StepExtractor ..> StepExtractionException : throws
```

## harness: the measurement contract

```mermaid
classDiagram
  direction LR

  class HarnessInvocation {
    <<final, Builder>>
    +command() List~String~
    +arguments() List~String~
    +out() Path
  }
  class ReportParser {
    <<final>>
    +TOLERANCE$ double
    -calculator MetricCalculator
    +parse(Path file, ExpectedReport expected) AcceptanceReport
  }
  class ReportJson {
    <<final>>
    +checkShape(JsonNode)$ void
    +toReport(JsonNode)$ AcceptanceReport
    +toJson(AcceptanceReport)$ ObjectNode
    +toDecoding(JsonNode)$ Decoding
  }
  class ReportRule {
    <<enumeration>>
    REPORT_MISSING
    JSON
    SHAPE
    SCHEMA_VERSION
    ...29 rules in check order
  }
  class ReportViolationException {
    <<final>>
    -rule ReportRule
  }
  class ExpectedReport {
    <<final>>
    +SCHEMA_VERSION$ int
    +of(ResolvedProbe, CheckpointType)$ ExpectedReport
  }
  class PromptSetReader {
    <<final>>
    +read(Path file) PromptSet
  }
  class ProbeResolver {
    <<final>>
    +resolve(Probe) ResolvedProbe
  }
  class PromptSetException { <<final>> }

  ReportParser ..> ReportJson : uses
  ReportParser ..> ExpectedReport : checks against
  ReportParser ..> ReportViolationException : throws
  ReportParser --> MetricCalculator
  ReportViolationException --> ReportRule
  ProbeResolver --> Fingerprinter
  ProbeResolver --> PromptSetReader
  ProbeResolver ..> ProbeHasher : uses
  PromptSetReader ..> PromptSetException : throws
```

## stats: metrics

```mermaid
classDiagram
  direction LR

  class MetricCalculator {
    <<final>>
    +strategy(Estimator) EstimatorStrategy
    +alpha(Estimator, List~PromptCounts~) OptionalDouble
    +tau(Estimator, List~PromptCounts~) OptionalDouble
    +excludedPrompts(Estimator, List~PromptCounts~) int
    +alphaByPosition(List~PositionCount~) List~OptionalDouble~
    +pooledPerPrompt(List~SeedReport~) List~PromptCounts~
    +mean(List~Double~) double
    +sampleStd(List~Double~) OptionalDouble
  }
  class EstimatorStrategy {
    <<interface, Strategy>>
    +estimator() Estimator
    +alpha(List~PromptCounts~) OptionalDouble
    +tau(List~PromptCounts~) OptionalDouble
    +excludedPrompts(List~PromptCounts~) int
  }
  class TokenWeightedEstimator { <<final>> }
  class SimpleMeanEstimator { <<final>> }
  class PairedBootstrap {
    <<final>>
    +run(List~PromptCounts~ current, List~PromptCounts~ baseline, Function statistic, int resamples, double confidence, long seed)$ BootstrapInterval
    ~quantile(double[] sorted, double q)$ double
  }
  class BootstrapInterval {
    <<final>>
    -observed double
    -lower double
    -upper double
  }
  class LeastSquaresSlope {
    <<final>>
    +slope(List~Double~ y)$ double
  }
  class UndefinedStatisticException { <<final>> }

  PairedBootstrap ..> BootstrapInterval : returns
  PairedBootstrap ..> UndefinedStatisticException : throws

  MetricCalculator o-- "2" EstimatorStrategy
  EstimatorStrategy <|.. TokenWeightedEstimator
  EstimatorStrategy <|.. SimpleMeanEstimator
```

## exec: jobs and executors

```mermaid
classDiagram
  direction TB

  class Executor {
    <<interface, Strategy>>
    +name() String
    +submit(JobSpec) JobHandle
    +status(JobHandle) ExecutorStatus
    +cancel(JobHandle) void
  }
  class LocalExecutor {
    <<final>>
    +NAME$ String
    +EXIT_FILE$ String
    ~WRAPPER$ String
  }
  class JobSpec {
    <<final, Builder>>
    -jobId String
    -attempt int
    -command List~String~
    -workingDir Path
    -runDir Path
  }
  class MeasurementSpec {
    <<final>>
    -checkpoint Checkpoint
    -probe ResolvedProbe
    -harnessCommand List~String~
    -rawDir Path
    -executor String
    +runDir(int attempt) Path
    +reportPath(int attempt) Path
    +invocation(int attempt) HarnessInvocation
    +jobSpec(String id, int attempt) JobSpec
    +expectedReport() ExpectedReport
  }
  class Job {
    <<final>>
    -id String
    -state JobState
    -attempt int
    -handle Optional~JobHandle~
    -history List~StateChange~
    +created(String, MeasurementSpec, Instant)$ Job
    +restore(...)$ Job
    +submitted(JobHandle, Instant) Job
    +running(Instant, String) Job
    +requeued(Instant, String) Job
    +succeeded(Instant) Job
    +failed(FailureReason, String, Instant) Job
    +cancelled(Instant, String) Job
    +retried(RetryPolicy, Instant) Job
  }
  class JobState {
    <<enumeration, State>>
    CREATED
    SUBMITTED
    RUNNING
    SUCCEEDED
    FAILED
    CANCELLED
    +checkTransition(JobState, JobState)$ void
  }
  class StateChange {
    <<final>>
    -from Optional~JobState~
    -to JobState
    -at Instant
    -attempt int
    -cause String
    -failureReason Optional~FailureReason~
  }
  class FailureReason {
    <<enumeration>>
    +forExitCode(int)$ FailureReason
  }
  class RetryPolicy {
    <<final>>
    -maxRetries int
    +allowsRetry(Job) boolean
    +isRetryable(FailureReason)$ boolean
  }
  class JobPoller {
    <<final>>
    +poll(Job) Result
  }
  class JobHandle {
    <<final>>
    -executor String
    -nativeId String
    -runDir Path
    -submittedAt Instant
    -nativeStartTime Optional~Instant~
  }
  class ExecutorStatus {
    <<final>>
    -kind Kind
    -failureReason Optional~FailureReason~
    -exitCode OptionalInt
    -startedAt Optional~Instant~
    -endedAt Optional~Instant~
  }
  class JobIds {
    <<interface>>
    +next() String
  }
  class TimestampJobIds { <<final>> }
  class IllegalJobTransitionException { <<final>> }

  Executor <|.. LocalExecutor
  Executor ..> JobSpec : runs
  Executor ..> JobHandle : returns
  Executor ..> ExecutorStatus : reports
  Job *-- MeasurementSpec
  Job *-- "1..*" StateChange
  Job o-- "0..1" JobHandle
  Job --> JobState
  MeasurementSpec ..> JobSpec : builds per attempt
  StateChange --> JobState
  StateChange --> FailureReason
  JobState ..> IllegalJobTransitionException : throws
  Job ..> RetryPolicy : consults on retry
  JobPoller --> Executor
  JobPoller --> ReportParser
  JobIds <|.. TimestampJobIds
```

`ExecutorStatus.Kind` is `QUEUED`, `RUNNING`, `EXITED`, `LOST`, `FAILED` (with a reason),
`CANCELLED`, or `UNRESOLVED` (the poll changes nothing; DECISIONS.md D60).

## exec.slurm: the Slurm executor

```mermaid
classDiagram
  direction TB

  class Executor {
    <<interface, Strategy>>
  }
  class SlurmExecutor {
    <<final>>
    +NAME$ String
    +SCRIPT_FILE$ String
    +UNRESOLVED_GRACE$ Duration
    -config SlurmConfig
    ~options(JobSpec) List~String~
    ~script(JobSpec)$ String
  }
  class SlurmCli {
    <<final, Adapter>>
    +SQUEUE_FORMAT$ String
    +SACCT_FORMAT$ String
    +TIME_FORMAT$ String
    +submit(List~String~, Path, List~String~, Set~String~) SlurmJobId
    +queue(SlurmJobId) Optional~QueueEntry~
    +queueByName(String, String) List~QueueEntry~
    +accounting(SlurmJobId) Optional~AccountingRecord~
    +cancel(SlurmJobId) void
  }
  class CommandRunner {
    <<interface, Strategy>>
    +run(List~String~, Map~String, String~, Set~String~) CommandResult
  }
  class ProcessCommandRunner {
    <<final>>
    -timeout Duration
  }
  class CommandResult {
    <<final>>
    -argv List~String~
    -exitCode int
    -stdout String
    -stderr String
  }
  class SlurmJobId {
    <<final>>
    -id String
    -cluster Optional~String~
    +parse(String)$ SlurmJobId
  }
  class SlurmState {
    <<enumeration>>
    +group() Group
    +parse(String)$ Optional~SlurmState~
  }
  class QueueEntry {
    <<final>>
    +state() Optional~SlurmState~
    +start() Optional~Instant~
    +isAlive() boolean
  }
  class AccountingRecord {
    <<final>>
    +state() Optional~SlurmState~
    +exitCode() OptionalInt
    +signal() OptionalInt
  }
  class ShellQuote {
    <<final>>
    +quote(String)$ String
  }

  Executor <|.. SlurmExecutor
  SlurmExecutor --> SlurmCli
  SlurmExecutor ..> ShellQuote : quotes the script
  SlurmCli --> CommandRunner
  CommandRunner <|.. ProcessCommandRunner
  CommandRunner ..> CommandResult : returns
  SlurmCli ..> SlurmJobId
  SlurmCli ..> QueueEntry : parses squeue
  SlurmCli ..> AccountingRecord : parses sacct
  QueueEntry --> SlurmState
  AccountingRecord --> SlurmState
```

Test doubles: `ReplayCommandRunner` answers with recorded or synthetic fixture output
(`fixtures/slurm/`), and `FakeSlurm` simulates a cluster that runs batch scripts locally.

## store: the state directory

```mermaid
classDiagram
  direction TB

  class JobRepository {
    <<interface, Repository>>
    +save(Job) void
    +find(String id) Optional~Job~
    +all() List~Job~
  }
  class ResultRepository {
    <<interface, Repository>>
    +append(Measurement) void
    +history(String target, String probeHash) List~Measurement~
    +find(String fingerprint, String probeHash) List~Measurement~
    +latest(String fingerprint, String probeHash) Optional~Measurement~
    +locate(Measurement) Path
  }
  class FileJobRepository { <<final>> }
  class FileResultRepository { <<final>> }
  class BaselineRepository {
    <<interface, Repository>>
    +get(String target) Optional~Baseline~
    +set(Baseline) void
  }
  class FileBaselineRepository { <<final>> }
  class DetectionLog {
    <<interface, Repository>>
    +append(DetectionRecord) void
    +all() List~DetectionRecord~
  }
  class FileDetectionLog { <<final>> }
  class FileFingerprintCache {
    <<final>>
    +FILE$ String
  }
  class DetectionRecord {
    <<final>>
    -kind String
    -subject DetectionSubject
    -detector Optional~String~
    -observed OptionalDouble
    -threshold OptionalDouble
    -baselineJobId Optional~String~
    -explanation String
    +of(DetectionEvent)$ DetectionRecord
  }
  class JsonCodec {
    <<final>>
    +jobJson(Job) ObjectNode
    +job(JsonNode) Job
    +measurementJson(Measurement) ObjectNode
    +measurement(JsonNode) Measurement
  }
  class AtomicFiles {
    <<final>>
    +write(Path, byte[])$ void
    +writeNew(Path, byte[])$ void
  }
  class StateLock {
    <<final>>
    +LOCK_FILE$ String
    +acquire(Path stateDir, String command) Held
    +holder(Path stateDir) Optional~LockHolder~
  }
  class Held {
    <<final>>
    +close() void
  }
  class LockHolder {
    <<final>>
    -host String
    -pid long
    -pidStart Optional~Instant~
    -slurmJobId Optional~String~
    -acquiredAt Instant
    -command String
  }
  class HostIdentity {
    <<interface>>
    +hostname() String
    +pid() long
    +processStart() Optional~Instant~
    +slurmJobId() Optional~String~
  }
  class ProcessTable {
    <<interface>>
    +isAlive(long pid, Optional~Instant~ start) boolean
  }
  class SlurmJobTable {
    <<interface>>
    +isAlive(String slurmJobId) boolean
  }
  class SqueueJobTable { <<final>> }
  class SystemHostIdentity { <<final>> }
  class SystemProcessTable { <<final>> }
  class StoreException { <<final>> }
  class StateLockException { <<final>> }

  JobRepository <|.. FileJobRepository
  BaselineRepository <|.. FileBaselineRepository
  DetectionLog <|.. FileDetectionLog
  FingerprintCache <|.. FileFingerprintCache
  DetectionLog ..> DetectionRecord
  ResultRepository <|.. FileResultRepository
  FileJobRepository --> JsonCodec
  FileResultRepository --> JsonCodec
  FileJobRepository ..> AtomicFiles : writes with
  FileResultRepository ..> AtomicFiles : writes with
  StateLock ..> Held : returns
  StateLock ..> LockHolder : records
  StateLock --> HostIdentity
  StateLock --> ProcessTable
  StateLock --> SlurmJobTable
  SlurmJobTable <|.. SqueueJobTable
  SqueueJobTable --> SlurmCli
  HostIdentity <|.. SystemHostIdentity
  ProcessTable <|.. SystemProcessTable
  StateLock ..> StateLockException : throws
  FileResultRepository ..> StoreException : throws
```

Test doubles (in `src/test`) give each interface its second implementation: `FakeExecutor`,
`ScriptedExecutor`, `InMemoryJobRepository`, `InMemoryResultRepository`,
`InMemoryBaselineRepository`, `InMemoryDetectionLog`, and test-local `HostIdentity`,
`ProcessTable`, `SlurmJobTable`, `Notifier`, `RegressionDetector`, `History`, and counting
`Fingerprinter` fakes.

## trigger: which checkpoints get measured

```mermaid
classDiagram
  direction LR

  class TriggerRule {
    <<interface>>
    +describe() String
    +evaluate(Checkpoint, ResolvedProbe, History) TriggerDecision
  }
  class TriggerChain {
    <<final, Chain of Responsibility>>
    -rules List~TriggerRule~
    +decide(Checkpoint, ResolvedProbe, History) Outcome
  }
  class Outcome {
    <<final>>
    +accepted() boolean
    +rule() Optional~String~
    +reason() String
  }
  class TriggerDecision {
    <<final>>
    +accept(String)$ TriggerDecision
    +reject(String)$ TriggerDecision
    +abstain()$ TriggerDecision
  }
  class History {
    <<interface>>
    +hasResult(String fingerprint, String probeHash) boolean
    +jobs(String fingerprint, String probeHash) List~Job~
    +activeJobs(String target) int
  }
  class RepositoryHistory { <<final>> }
  class NotAlreadyMeasuredRule { <<final>> }
  class MaxPendingRule { <<final>> }
  class AlwaysFinalRule { <<final>> }
  class EveryNStepsRule { <<final>> }

  TriggerChain o-- "*" TriggerRule
  TriggerChain ..> Outcome : returns
  TriggerRule ..> TriggerDecision : returns
  TriggerRule ..> History : reads
  TriggerRule <|.. NotAlreadyMeasuredRule
  TriggerRule <|.. MaxPendingRule
  TriggerRule <|.. AlwaysFinalRule
  TriggerRule <|.. EveryNStepsRule
  History <|.. RepositoryHistory
  RepositoryHistory --> JobRepository
  RepositoryHistory --> ResultRepository
```

## detect: regression detection

```mermaid
classDiagram
  direction TB

  class RegressionDetector {
    <<interface, Strategy>>
    +describe() String
    +metric() Metric
    +evaluate(Measurement current, Optional~Measurement~ baseline, List~Measurement~ history) DetectorVerdict
  }
  class BaselineDetector {
    <<abstract, Template Method>>
    +evaluate(...) DetectorVerdict
    #compare(Measurement current, Measurement baseline)* DetectorVerdict
  }
  class PairedBootstrapDetector { <<final>> }
  class AbsoluteDropDetector { <<final>> }
  class NoiseFloorDetector {
    <<final>>
    ~floor() double
  }
  class TrendDetector { <<final>> }
  class Comparability {
    <<final>>
    +mismatch(Measurement, Measurement)$ Optional~String~
  }
  class DetectorSuite {
    <<final>>
    +run(Measurement, Optional~Measurement~, List~Measurement~) List~DetectorVerdict~
  }
  class DetectorVerdict {
    <<final>>
    -kind Kind
    -detector String
    -metric Metric
    -observed OptionalDouble
    -threshold OptionalDouble
    -intervalLower OptionalDouble
    -intervalUpper OptionalDouble
    -baselineJobId Optional~String~
    -explanation String
  }
  class Kind {
    <<enumeration>>
    OK
    REGRESSION
    ERROR
    INSUFFICIENT_DATA
  }

  RegressionDetector <|.. BaselineDetector
  RegressionDetector <|.. TrendDetector
  BaselineDetector <|-- PairedBootstrapDetector
  BaselineDetector <|-- AbsoluteDropDetector
  BaselineDetector <|-- NoiseFloorDetector
  BaselineDetector ..> Comparability : guard
  TrendDetector ..> Comparability : filters history
  PairedBootstrapDetector ..> PairedBootstrap : uses
  TrendDetector ..> LeastSquaresSlope : uses
  DetectorSuite o-- "*" RegressionDetector
  DetectorSuite ..> DetectorVerdict : returns
  DetectorVerdict --> Kind
```

## events: the event bus

```mermaid
classDiagram
  direction TB

  class Event {
    <<interface>>
    +at() Instant
  }
  class Subscriber {
    <<interface>>
    +on(E event) void
  }
  class EventBus {
    <<final, Observer>>
    +subscribe(Class~E~ type, String name, Subscriber subscriber) void
    +publish(Event) void
  }
  class MeasurementStored {
    <<final>>
    -measurement Measurement
    -resultFile Path
  }
  class SubscriberFailed {
    <<final>>
    -subscriber String
    -error String
  }
  class BaselinePinned {
    <<final>>
    -baseline Baseline
  }
  class DetectionSubject {
    <<final>>
    -target String
    -probeId String
    -probeHash String
    -step long
    -jobId String
    -resultFile Path
  }
  class DetectionEvent {
    <<abstract>>
    +of(Instant, DetectionSubject, DetectorVerdict)$ DetectionEvent
    +verdict()* Optional~DetectorVerdict~
    +kind()* String
    +explanation()* String
  }
  class DetectionOk { <<final>> }
  class RegressionDetected { <<final>> }
  class DetectionError { <<final>> }
  class DetectionInsufficientData { <<final>> }
  class DetectionDeferred { <<final>> }

  Event <|.. MeasurementStored
  Event <|.. SubscriberFailed
  Event <|.. BaselinePinned
  Event <|.. DetectionEvent
  DetectionEvent <|-- DetectionOk
  DetectionEvent <|-- RegressionDetected
  DetectionEvent <|-- DetectionError
  DetectionEvent <|-- DetectionInsufficientData
  DetectionEvent <|-- DetectionDeferred
  DetectionEvent *-- DetectionSubject
  EventBus o-- "*" Subscriber
  EventBus ..> SubscriberFailed : publishes on failure
```

## notify and action: alerts

```mermaid
classDiagram
  direction LR

  class Notifier {
    <<interface, Strategy>>
    +name() String
    +notify(DetectionEvent) void
  }
  class ConsoleNotifier { <<final>> }
  class LogFileNotifier {
    <<final>>
    +FILE$ String
  }
  class AlertFormat {
    <<final>>
    +line(DetectionEvent)$ String
  }
  class RegressionAction {
    <<interface, Command>>
    +name() String
    +execute(RegressionDetected) void
  }
  class NotifyAction {
    <<final>>
    -notifiers List~Notifier~
  }
  class ActionFailedException { <<final>> }

  Notifier <|.. ConsoleNotifier
  Notifier <|.. LogFileNotifier
  ConsoleNotifier ..> AlertFormat : uses
  LogFileNotifier ..> AlertFormat : uses
  RegressionAction <|.. NotifyAction
  NotifyAction o-- "*" Notifier
  NotifyAction ..> ActionFailedException : throws
```

## Packages

| Package | Populated in |
|---|---|
| `app`, `config`, `domain`, `fingerprint` | M0–M2 |
| `discovery` | M1 (step extraction), M2 (completion policies, inspector), M4 (checkpoint sources) |
| `harness`, `exec`, `store`, `stats` | M1–M2 |
| `detect`, `events`, `notify`, `action` | M3 |
| `trigger` | M4 |
| `exec.slurm` | M5 |
| `report` | M6 |
