package dev.draftwatch.scoring;

/** The four probes of a family: two 1,000-prompt reference probes and two 100-prompt live ones. */
public enum ProbeKind {
  CHAT("chat", true),
  CODE("code", true),
  CHAT_LIVE("chat-live", false),
  CODE_LIVE("code-live", false);

  private final String suffix;
  private final boolean reference;

  ProbeKind(String suffix, boolean reference) {
    this.suffix = suffix;
    this.reference = reference;
  }

  /** The suffix of the probe id, as in {@code qwen-chat-live}. */
  public String suffix() {
    return suffix;
  }

  /** Whether this is a reference probe, which sets the ground truth. */
  public boolean isReference() {
    return reference;
  }
}
