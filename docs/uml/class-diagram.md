# Class diagram

Updated at the end of every milestone (DECISIONS.md D1). Shows the classes that exist in
`src/main/java` now, not the planned design; for the planned design see `ARCHITECTURE.md`.
Accessors that only return a field are omitted; every domain and config class is immutable
(private final fields, static factory or builder, no setters).

**As of:** M1: domain, config, hashing.

## app: entry point and CLI commands

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
    +Bootstrap(PrintStream out, PrintStream err)
    +cli() Cli
    ~fingerprinter(FingerprintMethod)$ Fingerprinter
  }
  class Cli {
    <<final>>
    +EXIT_OK$ = 0
    +EXIT_FAILURE$ = 1
    +EXIT_USAGE$ = 2
    -usages List~CommandUsage~
    -commands Map~String, CliCommand~
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
    -out PrintStream
    -err PrintStream
    +fail(String message) int
    +requireNoArguments(String command, List~String~ args) int
  }
  class CommandUsage {
    <<final>>
    -name String
    -arguments String
    -summary String
    +of(String, String, String)$ CommandUsage
    +synopsis() String
  }
  class InitCommand {
    <<final>>
    +run(CommandContext, List~String~) int
  }
  class ValidateCommand {
    <<final>>
    -loader ConfigLoader
    -resolvers Function~FingerprintMethod, ProbeResolver~
    +run(CommandContext, List~String~) int
  }

  Main ..> Bootstrap : creates
  Bootstrap ..> Cli : creates
  Bootstrap ..> InitCommand : creates
  Bootstrap ..> ValidateCommand : creates
  Cli o-- "*" CommandUsage
  Cli o-- "*" CliCommand
  Cli ..> CommandContext : creates
  CliCommand <|.. InitCommand
  CliCommand <|.. ValidateCommand
  ValidateCommand --> ConfigLoader
  ValidateCommand ..> ProbeResolver : uses
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
  class ProbeResolver {
    <<final>>
    -fingerprinter Fingerprinter
    -promptSetReader PromptSetReader
    +resolve(Probe) ResolvedProbe
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
  ProbeResolver --> Fingerprinter
  ProbeResolver --> PromptSetReader
  ProbeResolver ..> ProbeHasher : uses
  ProbeResolver ..> ResolvedProbe : creates
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

## fingerprint, discovery, harness: reading checkpoint and prompt files

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
    +BLOCK_BYTES$ = 1 MiB
    +BLOCK_COUNT$ = 8
    #contentDigest(Path, long) byte[]
    ~blockOffset(int i, long size) long
  }
  class FullFileFingerprinter {
    <<final>>
    #contentDigest(Path, long) byte[]
  }
  class FingerprintMethod {
    <<enumeration>>
    SAMPLED
    FULL
  }
  class FingerprintException {
    <<final>>
    -path Path
  }
  class Sha256 {
    <<final>>
    +newDigest()$ MessageDigest
    +hex(byte[])$ String
    +toHex(byte[])$ String
  }
  class StepExtractor {
    <<final>>
    +TRAINER_STATE_FILE$ String
    -stepRegex Pattern
    +extract(Path checkpointDir) long
  }
  class StepExtractionException {
    <<final>>
    -checkpointDir Path
  }
  class PromptSetReader {
    <<final>>
    +read(Path file) PromptSet
  }
  class PromptSetException {
    <<final>>
    -file Path
  }

  Fingerprinter <|.. WeightFileFingerprinter
  WeightFileFingerprinter <|-- SampledBlockFingerprinter
  WeightFileFingerprinter <|-- FullFileFingerprinter
  WeightFileFingerprinter --> FingerprintMethod
  WeightFileFingerprinter ..> Sha256 : uses
  WeightFileFingerprinter ..> FingerprintException : throws
  StepExtractor ..> StepExtractionException : throws
  PromptSetReader ..> Sha256 : uses
  PromptSetReader ..> PromptSetException : throws
  PromptSetReader ..> PromptSet : creates
```

## Packages

| Package | Populated in |
|---|---|
| `app`, `config`, `domain`, `fingerprint` | M0–M1 |
| `discovery` | M1 (step extraction); M4 (checkpoint sources, completion policies) |
| `harness` | M1 (prompt file reader); M2 (invocation, report parser) |
| `exec`, `store`, `stats` | M2 |
| `detect`, `events`, `notify`, `action` | M3 |
| `trigger` | M4 |
| `report` | M6 |
