package dev.draftwatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.testing.FakeHarness;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A throwaway draftwatch project for CLI end-to-end tests: a config whose harness is the fake
 * harness, a draft, a prompt file, and checkpoint directories. All numbers it produces are
 * synthetic (fixtures under {@code src/test/resources/fixtures}).
 */
final class Project {
  private final Path dir;
  private final String fixture = "synthetic_three_prompts.json";
  private final Map<String, String> fakeEnv = new HashMap<>();
  private String checkpointType = "full";
  private String executorType = "local";
  private int maxRetries = 0;

  Project(Path dir) {
    this.dir = dir;
  }

  Project fakeEnv(String name, String value) {
    fakeEnv.put(name, value);
    return this;
  }

  Project adapter() {
    checkpointType = "adapter";
    return this;
  }

  Project executor(String type) {
    executorType = type;
    return this;
  }

  Project maxRetries(int n) {
    maxRetries = n;
    return this;
  }

  Path dir() {
    return dir;
  }

  Path config() {
    return dir.resolve("draftwatch.yaml");
  }

  Path state() {
    return dir.resolve(".draftwatch");
  }

  /** A complete checkpoint {@code checkpoint-<step>} with a weight file and the DONE marker. */
  Path checkpoint(int step, byte weight) {
    try {
      Path ckpt = Files.createDirectories(dir.resolve("runs/checkpoint-" + step));
      Files.write(ckpt.resolve("model.safetensors"), new byte[] {weight, 1, 2, 3});
      Files.writeString(ckpt.resolve("DONE"), "");
      return ckpt;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes the draft, prompts, base model, and config. */
  Project write() {
    try {
      Files.createDirectories(dir.resolve("draft"));
      Files.write(dir.resolve("draft/model.safetensors"), new byte[] {7, 7, 7});
      Files.createDirectories(dir.resolve("base"));
      FakeHarness.writePrompts(dir, 3);
      Map<String, String> env = new HashMap<>(fakeEnv);
      env.put("DRAFTWATCH_FAKE_FIXTURE", FakeHarness.fixture(fixture).toString());
      String command = new ObjectMapper().writeValueAsString(FakeHarness.command(env));
      List<String> lines =
          List.of(
              "executor: { type: " + executorType + ", max_retries: " + maxRetries
                  + (executorType.equals("slurm") ? ", slurm: { requeue_on_preempt: true }" : "")
                  + " }",
              "harness: { command: " + command + " }",
              "probes:",
              "  - id: chat",
              "    draft: { id: my-draft, path: draft, structure: chain }",
              "    prompts: { path: prompts.jsonl }",
              "    decoding: { temperature: 0, max_new_tokens: 16, num_speculative_tokens: 3,"
                  + " dtype: bfloat16 }",
              "    seeds: [0]",
              "targets:",
              "  - name: run",
              "    checkpoint_dirs: [runs]",
              "    checkpoint_type: " + checkpointType,
              checkpointType.equals("adapter") ? "    base_model: base" : "",
              "    completion: { marker: DONE }",
              "    probes: [chat]",
              "");
      Files.writeString(config(), String.join("\n", lines));
      return this;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
