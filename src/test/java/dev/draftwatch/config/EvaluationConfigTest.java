package dev.draftwatch.config;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.draftwatch.domain.Decoding;
import dev.draftwatch.domain.Estimator;
import dev.draftwatch.domain.Metric;
import dev.draftwatch.domain.Probe;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

/** The evaluation's configuration (evaluation/draftwatch.eval.yaml) matches docs/EVALUATION.md. */
public class EvaluationConfigTest {
  private static final Path FILE = Path.of("evaluation", "draftwatch.eval.yaml");

  private static DraftwatchConfig config() throws Exception {
    return new ConfigValidator().validate(new YAMLMapper().readTree(FILE.toFile()), FILE);
  }

  @Test
  public void evaluationConfigValidatesAndMatchesTheDesign() throws Exception {
    DraftwatchConfig config = config();

    assertEquals(
        List.of("qwen-run1", "smol-run1", "qwen-run2", "smol-run2"),
        config.targets().stream().map(TargetConfig::name).collect(Collectors.toList()));
    List<TriggerSpec> triggers =
        List.of(
            TriggerSpec.of(TriggerSpec.Kind.NOT_ALREADY_MEASURED),
            TriggerSpec.of(TriggerSpec.Kind.MAX_PENDING, 2),
            TriggerSpec.of(TriggerSpec.Kind.ALWAYS_FINAL),
            TriggerSpec.of(TriggerSpec.Kind.EVERY_N_STEPS, 1));
    List<DetectorSpec> detectors =
        List.of(
            PairedBootstrapSpec.withDefaults(Metric.ALPHA),
            AbsoluteDropSpec.of(Metric.ALPHA, 0.02),
            TrendSpec.of(Metric.ALPHA, 4, -0.005));
    for (TargetConfig target : config.targets()) {
      assertEquals(target.name(), triggers, target.triggers());
      assertEquals(target.name(), CompletionSpec.marker("COMPLETE"), target.completion());
      assertEquals(target.name(), detectors, target.detectors());
      String family = target.name().substring(0, target.name().indexOf('-'));
      assertEquals(
          target.name(),
          List.of(family + "-chat", family + "-chat-live", family + "-code", family + "-code-live"),
          target.probeIds());
    }

    assertEquals(8, config.probes().size());
    Decoding decoding = Decoding.of(BigDecimal.ZERO, 128, 4, "bfloat16");
    for (Probe probe : config.probes()) {
      assertEquals(probe.id(), decoding, probe.decoding());
      assertEquals(probe.id(), Estimator.TOKEN_WEIGHTED, probe.estimator());
      assertEquals(probe.id(), List.of(0), probe.seeds());
    }

    assertEquals(ExecutorType.SLURM, config.executor().type());
    SlurmConfig slurm = config.executor().slurm().orElseThrow();
    assertEquals("gpu-short", slurm.partition().orElseThrow());
    assertEquals("gpu:h200:1", slurm.gres().orElseThrow());
  }
}
