package dev.draftwatch.exec.slurm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.draftwatch.testing.FakeHarness;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * {@code scripts/record_slurm_fixtures.py} records with exactly the arguments and environment
 * {@link SlurmCli} uses, so its recordings test the real queries (DECISIONS.md D64).
 */
public class RecordingScriptTest {
  private static JsonNode printArgv(String jobId) throws Exception {
    Process p =
        new ProcessBuilder(
                "python3",
                FakeHarness.projectDir().resolve("scripts/record_slurm_fixtures.py").toString(),
                "--print-argv",
                jobId)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    byte[] out = p.getInputStream().readAllBytes();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, p.exitValue());
    return new ObjectMapper().readTree(new String(out, StandardCharsets.UTF_8));
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.textValue()));
    return out;
  }

  @Test
  public void recorderUsesTheJavaQueriesAndEnvironment() throws Exception {
    JsonNode argv = printArgv("4242");
    SlurmJobId job = SlurmJobId.parse("4242");
    assertEquals(SlurmCli.squeueArgv(job), strings(argv.get("squeue")));
    assertEquals(SlurmCli.sacctArgv(job), strings(argv.get("sacct")));
    Map<String, String> set = new TreeMap<>();
    for (Map.Entry<String, JsonNode> e : argv.get("set").properties()) {
      set.put(e.getKey(), e.getValue().textValue());
    }
    assertEquals(Map.of("SLURM_TIME_FORMAT", SlurmCli.TIME_FORMAT), set);
    Set<String> unset = new TreeSet<>(strings(argv.get("unset")));
    assertEquals(new TreeSet<>(SlurmCli.FORMAT_VARIABLES), unset);
  }
}
