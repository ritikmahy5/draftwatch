package dev.draftwatch.testing;

import dev.draftwatch.exec.ExecutorException;
import dev.draftwatch.exec.slurm.CommandResult;
import dev.draftwatch.exec.slurm.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link CommandRunner} that answers with recorded results (DECISIONS.md D64). A command is
 * answered only by a result recorded for exactly its argv, so the fixtures also pin the
 * arguments the code builds. Results come from the current {@link #observe observation}, which
 * each poll replaces, or from {@link #always standing} ones such as a successful scancel.
 */
public final class ReplayCommandRunner implements CommandRunner {
  /** One command as it was run. */
  public static final class Call {
    private final List<String> argv;
    private final Map<String, String> set;
    private final Set<String> unset;

    private Call(List<String> argv, Map<String, String> set, Set<String> unset) {
      this.argv = List.copyOf(argv);
      this.set = Map.copyOf(set);
      this.unset = Set.copyOf(unset);
    }

    public List<String> argv() {
      return argv;
    }

    public Map<String, String> set() {
      return set;
    }

    public Set<String> unset() {
      return unset;
    }
  }

  private final List<CommandResult> observation = new ArrayList<>();
  private final List<CommandResult> standing = new ArrayList<>();
  private final List<Call> calls = new ArrayList<>();
  private ExecutorException failure;

  /** Replaces the current observation's results. */
  public ReplayCommandRunner observe(List<CommandResult> results) {
    observation.clear();
    observation.addAll(results);
    return this;
  }

  /** Adds a result available to every later call. */
  public ReplayCommandRunner always(CommandResult result) {
    standing.add(result);
    return this;
  }

  /** Makes every later call fail as if the command could not be run. */
  public ReplayCommandRunner failWith(ExecutorException e) {
    failure = e;
    return this;
  }

  /** Every command run so far, oldest first. */
  public List<Call> calls() {
    return List.copyOf(calls);
  }

  /** The argv of every command run so far whose program is {@code program}. */
  public List<List<String>> calls(String program) {
    List<List<String>> out = new ArrayList<>();
    for (Call c : calls) {
      if (c.argv.get(0).equals(program)) {
        out.add(c.argv);
      }
    }
    return out;
  }

  @Override
  public CommandResult run(List<String> argv, Map<String, String> set, Set<String> unset) {
    calls.add(new Call(argv, set, unset));
    if (failure != null) {
      throw failure;
    }
    for (List<CommandResult> pool : List.of(observation, standing)) {
      for (CommandResult r : pool) {
        if (r.argv().equals(argv)) {
          return r;
        }
      }
    }
    List<List<String>> recorded = new ArrayList<>();
    observation.forEach(r -> recorded.add(r.argv()));
    standing.forEach(r -> recorded.add(r.argv()));
    throw new AssertionError("no recorded result for " + argv + "; recorded: " + recorded);
  }
}
