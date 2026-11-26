package dev.draftwatch.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EvaluationNamesTest {
  @Test
  public void parsesTargetNames() {
    TargetName t = EvaluationNames.target("qwen-run2");
    assertEquals("qwen", t.family());
    assertEquals(Run.RUN2, t.run());
    assertEquals(Run.RUN1, EvaluationNames.target("smol-run1").run());
  }

  @Test
  public void parsesProbeIds() {
    ProbeName live = EvaluationNames.probe("smol-code-live");
    assertEquals("smol", live.family());
    assertEquals(ProbeKind.CODE_LIVE, live.kind());
    assertFalse(live.kind().isReference());
    ProbeName chat = EvaluationNames.probe("qwen-chat");
    assertEquals("qwen", chat.family());
    assertEquals(ProbeKind.CHAT, chat.kind());
    assertTrue(chat.kind().isReference());
    assertEquals(ProbeKind.CHAT_LIVE, EvaluationNames.probe("qwen-chat-live").kind());
  }

  @Test
  public void rejectsUnknownNames() {
    IllegalArgumentException t =
        assertThrows(IllegalArgumentException.class, () -> EvaluationNames.target("qwen-run3"));
    assertTrue(t.getMessage().contains("qwen-run3"));
    IllegalArgumentException p =
        assertThrows(IllegalArgumentException.class, () -> EvaluationNames.probe("qwen-math"));
    assertTrue(p.getMessage().contains("qwen-math"));
    assertThrows(IllegalArgumentException.class, () -> EvaluationNames.probe("-chat"));
  }
}
