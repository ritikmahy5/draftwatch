# Class diagram

Updated at the end of every milestone (DECISIONS.md D1). Shows the classes that exist in
`src/main/java` now, not the planned design; for the planned design see `ARCHITECTURE.md`.

**As of:** M0 — Scaffold.

```mermaid
classDiagram
  direction LR

  class Main {
    <<final>>
    +main(String[] args)$ void
  }

  class Bootstrap {
    <<final>>
    ~COMMANDS$ List~CommandUsage~
    -out PrintStream
    -err PrintStream
    +Bootstrap(PrintStream out, PrintStream err)
    +cli() Cli
  }

  class Cli {
    <<final>>
    +EXIT_OK$ int = 0
    +EXIT_USAGE$ int = 2
    -commands List~CommandUsage~
    -out PrintStream
    -err PrintStream
    +Cli(List~CommandUsage~ commands, PrintStream out, PrintStream err)
    +run(List~String~ args) int
    +usage() String
  }

  class CommandUsage {
    <<final>>
    -name String
    -arguments String
    -summary String
    +of(String name, String arguments, String summary)$ CommandUsage
    +name() String
    +arguments() String
    +summary() String
    +synopsis() String
  }

  Main ..> Bootstrap : creates
  Bootstrap ..> Cli : creates
  Bootstrap --> "*" CommandUsage : COMMANDS
  Cli o-- "*" CommandUsage : lists
```

## Packages

Every package below exists with a `package-info.java`. Classes are added milestone by milestone
(`ROADMAP.md`).

| Package | First populated in |
|---|---|
| `app` | M0 |
| `config`, `domain`, `fingerprint` | M1 |
| `discovery`, `trigger` | M1 (step extraction), M4 |
| `exec`, `harness`, `store`, `stats` | M2 |
| `detect`, `events`, `notify`, `action` | M3 |
| `report` | M6 |
