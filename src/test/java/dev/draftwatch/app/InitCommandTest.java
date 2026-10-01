package dev.draftwatch.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.draftwatch.config.ConfigLoader;
import dev.draftwatch.config.ConfigValidator;
import dev.draftwatch.config.DraftwatchConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InitCommandTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final CommandTestSupport cli = new CommandTestSupport();

  private Path configPath() {
    return tmp.getRoot().toPath().toAbsolutePath().resolve("project").resolve("draftwatch.yaml");
  }

  @Test
  public void writesTemplateAndCreatesStateDirectory() throws IOException {
    Path config = configPath();
    assertEquals(Cli.EXIT_OK, cli.run("init", "--config", config.toString()));
    assertArrayEquals(InitCommand.template(), Files.readAllBytes(config));
    Path stateDir = config.getParent().resolve(".draftwatch");
    assertTrue(Files.isDirectory(stateDir));
    assertEquals(
        "created " + config + "\ncreated " + stateDir + "\n"
            + "next: replace the placeholders, then run 'draftwatch validate'\n",
        cli.out());
    assertEquals("", cli.err());
  }

  @Test
  public void templateIsAStructurallyValidConfigForTheCreatedStateDirectory() {
    Path config = configPath();
    cli.run("init", "--config", config.toString());
    DraftwatchConfig loaded = new ConfigLoader(new ConfigValidator()).load(config);
    assertEquals(config.getParent().resolve(".draftwatch"), loaded.stateDir());
  }

  @Test
  public void neverOverwritesAnExistingConfig() throws IOException {
    Path config = configPath();
    Files.createDirectories(config.getParent());
    Files.writeString(config, "mine: true\n");
    assertEquals(Cli.EXIT_FAILURE, cli.run("init", "--config", config.toString()));
    assertEquals("mine: true\n", Files.readString(config));
    assertTrue(cli.err(), cli.err().contains("already exists; not overwritten"));
    assertEquals("", cli.out());
  }

  @Test
  public void rejectsArguments() {
    assertEquals(Cli.EXIT_USAGE, cli.run("init", "extra", "--config", configPath().toString()));
    assertTrue(cli.err(), cli.err().contains("draftwatch init: unexpected argument 'extra'"));
    assertTrue(Files.notExists(configPath()));
  }
}
