package dev.draftwatch.app;

import dev.draftwatch.config.DraftwatchConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * {@code draftwatch init}: writes a config template and creates the state directory next to it.
 * An existing config file is never overwritten.
 */
public final class InitCommand implements CliCommand {
  static final String TEMPLATE_RESOURCE = "draftwatch.template.yaml";

  @Override
  public int run(CommandContext context, List<String> args) {
    int usage = context.requireNoArguments("init", args);
    if (usage != Cli.EXIT_OK) {
      return usage;
    }
    Path configFile = context.configFile().toAbsolutePath().normalize();
    Path stateDir = configFile.getParent().resolve(DraftwatchConfig.DEFAULT_STATE_DIR);
    try {
      Files.createDirectories(configFile.getParent());
      Files.write(configFile, template(), StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException e) {
      return context.fail(configFile + " already exists; not overwritten");
    } catch (IOException e) {
      return context.fail("cannot write " + configFile + ": " + e.getMessage());
    }
    try {
      Files.createDirectories(stateDir);
    } catch (IOException e) {
      return context.fail("cannot create " + stateDir + ": " + e.getMessage());
    }
    context.out().println("created " + configFile);
    context.out().println("created " + stateDir);
    context.out().println("next: replace the placeholders, then run 'draftwatch validate'");
    return Cli.EXIT_OK;
  }

  /** The template's bytes, exactly as packaged. */
  static byte[] template() {
    try (InputStream in = InitCommand.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + TEMPLATE_RESOURCE);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
