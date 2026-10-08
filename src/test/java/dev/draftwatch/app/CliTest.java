package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class CliTest {
  private ByteArrayOutputStream outBytes;
  private ByteArrayOutputStream errBytes;
  private PrintStream outStream;
  private PrintStream errStream;
  private Cli cli;

  @Before
  public void setUp() {
    outBytes = new ByteArrayOutputStream();
    errBytes = new ByteArrayOutputStream();
    outStream = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    errStream = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    cli = new Bootstrap(outStream, errStream).cli();
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  private int run(String... args) {
    return cli.run(Arrays.asList(args));
  }

  /** A command that records what it was given, for testing dispatch. */
  private static final class Recorder implements CliCommand {
    final List<Path> configFiles = new ArrayList<>();
    final List<List<String>> argLists = new ArrayList<>();

    @Override
    public int run(CommandContext context, List<String> args) {
      configFiles.add(context.configFile());
      argLists.add(args);
      return 7;
    }
  }

  private Recorder recorderCli() {
    Recorder recorder = new Recorder();
    cli =
        new Cli(
            List.of(CommandUsage.of("status", "", "s")),
            Map.of("status", recorder),
            outStream,
            errStream);
    return recorder;
  }

  // --- help -------------------------------------------------------------------------------------

  @Test
  public void helpPrintsUsageToStdoutAndExitsZero() {
    assertEquals(Cli.EXIT_OK, run("--help"));
    assertTrue(out().startsWith("draftwatch - continuous integration for speculative decoding"));
    assertTrue(out().contains("Usage: draftwatch [--config <file>] <command> [arguments]"));
    assertEquals("", err());
  }

  @Test
  public void shortHelpAndHelpCommandMatchLongHelp() {
    run("--help");
    String expected = out();
    outBytes.reset();
    assertEquals(Cli.EXIT_OK, run("-h"));
    assertEquals(expected, out());
    outBytes.reset();
    assertEquals(Cli.EXIT_OK, run("help"));
    assertEquals(expected, out());
  }

  @Test
  public void helpListsEverySpecCommandWithItsSynopsis() {
    run("--help");
    List<String> synopses =
        Arrays.asList(
            "init",
            "validate",
            "watch [--once] [--interval 60s]",
            "submit <target> <ckpt>",
            "schedule [--interval 15m]",
            "unschedule",
            "status",
            "history <target> --probe <id>",
            "diff <ckptA> <ckptB> --probe <id>",
            "report [--out report.html]",
            "baseline <target> [<ckpt>]");
    for (String synopsis : synopses) {
      assertTrue("help is missing: " + synopsis, out().contains("  " + synopsis + " "));
    }
    assertEquals(synopses.size(), Bootstrap.COMMANDS.size());
  }

  @Test
  public void helpAlignsEverySummaryInOneColumn() {
    run("--help");
    int column = -1;
    for (String line : out().split("\n")) {
      if (!line.startsWith("  ")) {
        continue;
      }
      int summary = line.indexOf("  ", 2);
      while (line.charAt(summary) == ' ') {
        summary++;
      }
      if (column < 0) {
        column = summary;
      }
      assertEquals("misaligned: " + line, column, summary);
    }
    String configLine =
        "(?s).*\n  --config <file> +config file \\(default: \\./draftwatch\\.yaml\\)\n.*";
    assertTrue(out().matches(configLine));
  }

  // --- errors -----------------------------------------------------------------------------------

  @Test
  public void noArgumentsPrintsUsageToStderrAndExitsUsage() {
    assertEquals(Cli.EXIT_USAGE, run());
    assertEquals("", out());
    assertEquals(cli.usage(), err());
  }

  @Test
  public void unknownCommandIsNamedInError() {
    assertEquals(Cli.EXIT_USAGE, run("frobnicate"));
    assertEquals("", out());
    assertTrue(err().contains("unknown command 'frobnicate'"));
  }

  @Test
  public void listedButUnimplementedCommandSaysSo() {
    Cli partial =
        new Cli(
            List.of(
                CommandUsage.of("status", "", "show status"),
                CommandUsage.of("later", "", "not built yet")),
            Map.of("status", new Recorder()),
            outStream,
            errStream);
    assertEquals(Cli.EXIT_USAGE, partial.run(List.of("later")));
    assertEquals("", out());
    assertTrue(err().contains("command 'later' is not implemented yet"));
  }

  @Test
  public void everyListedCommandIsImplemented() {
    for (CommandUsage usage : Bootstrap.COMMANDS) {
      errBytes.reset();
      run(usage.name(), "--no-such-flag");
      assertFalse(usage.name(), err().contains("is not implemented yet"));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void implementedCommandMustAppearInHelp() {
    new Cli(List.of(), Map.of("status", new Recorder()), outStream, errStream);
  }

  // --- dispatch and --config --------------------------------------------------------------------

  @Test
  public void commandGetsDefaultConfigAndRemainingArguments() {
    Recorder recorder = recorderCli();
    assertEquals(7, run("status", "a", "b"));
    assertEquals(List.of(Paths.get("draftwatch.yaml")), recorder.configFiles);
    assertEquals(List.of(List.of("a", "b")), recorder.argLists);
  }

  @Test
  public void configOptionWorksBeforeOrAfterTheCommand() {
    Recorder recorder = recorderCli();
    run("--config", "x.yaml", "status");
    run("status", "--config=y.yaml", "a");
    assertEquals(List.of(Paths.get("x.yaml"), Paths.get("y.yaml")), recorder.configFiles);
    assertEquals(List.of(List.of(), List.of("a")), recorder.argLists);
  }

  @Test
  public void configOptionNeedsExactlyOneValue() {
    recorderCli();
    assertEquals(Cli.EXIT_USAGE, run("status", "--config"));
    assertTrue(err().contains("--config needs a file path"));
    errBytes.reset();
    assertEquals(Cli.EXIT_USAGE, run("--config=", "status"));
    assertTrue(err().contains("--config needs a file path"));
    errBytes.reset();
    assertEquals(Cli.EXIT_USAGE, run("--config", "a", "status", "--config", "b"));
    assertTrue(err().contains("--config given more than once"));
  }
}
