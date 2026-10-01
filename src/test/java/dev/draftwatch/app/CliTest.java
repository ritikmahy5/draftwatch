package dev.draftwatch.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CliTest {
  private ByteArrayOutputStream outBytes;
  private ByteArrayOutputStream errBytes;
  private Cli cli;

  @Before
  public void setUp() {
    outBytes = new ByteArrayOutputStream();
    errBytes = new ByteArrayOutputStream();
    cli =
        new Bootstrap(
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8))
            .cli();
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

  @Test
  public void helpPrintsUsageToStdoutAndExitsZero() {
    assertEquals(Cli.EXIT_OK, run("--help"));
    assertTrue(out().startsWith("draftwatch - continuous integration for speculative decoding"));
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
            "watch [--once]",
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
    assertEquals(Cli.EXIT_USAGE, run("watch", "--once"));
    assertEquals("", out());
    assertTrue(err().contains("command 'watch' is not implemented yet"));
  }

  @Test
  public void usageAlignsSummariesInOneColumn() {
    Cli small =
        new Cli(
            Arrays.asList(
                CommandUsage.of("a", "", "first"), CommandUsage.of("bbb", "<x>", "second")),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    String usage = small.usage();
    assertTrue(usage.contains("\n  a        first\n"));
    assertTrue(usage.contains("\n  bbb <x>  second\n"));
  }

  @Test
  public void emptyCommandListStillPrintsHelpOption() {
    Cli empty =
        new Cli(
            Collections.emptyList(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertTrue(empty.usage().contains("-h, --help"));
  }
}
