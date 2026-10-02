package dev.draftwatch.harness;

import dev.draftwatch.domain.WireNamed;

/**
 * The report validation rules of MEASUREMENT_CONTRACT.md, "Validation rules enforced by
 * ReportParser", in check order. Each wire name is the rule's stable id, shared with the fake
 * harness's {@code DRAFTWATCH_FAKE_CORRUPT}.
 */
public enum ReportRule implements WireNamed {
  REPORT_MISSING("report_missing"),
  JSON("json"),
  SHAPE("shape"),
  SCHEMA_VERSION("schema_version"),
  DRAFT_STRUCTURE("draft_structure"),
  ESTIMATOR("estimator"),
  DRAFT_ID("draft_id"),
  DECODING("decoding"),
  PROMPT_SET_SHA256("prompt_set_sha256"),
  NUM_PROMPTS("num_prompts"),
  SEEDS("seeds"),
  ADAPTER_HANDLING("adapter_handling"),
  PER_PROMPT_LENGTH("per_prompt_length"),
  PROMPT_INDICES("prompt_indices"),
  PROMPT_COUNTS("prompt_counts"),
  TOTALS("totals"),
  POSITION_LENGTHS("position_lengths"),
  POSITION_COUNTS("position_counts"),
  POSITION_MONOTONE("position_monotone"),
  POSITION_TOTALS("position_totals"),
  ALPHA_BY_POSITION("alpha_by_position"),
  POSITION_COUNTS_EXACT("position_counts_exact"),
  EXCLUDED_PROMPTS("excluded_prompts"),
  ALPHA_RANGE("alpha_range"),
  TAU_RANGE("tau_range"),
  ALPHA("alpha"),
  TAU("tau"),
  AGGREGATE_MEAN("aggregate_mean"),
  AGGREGATE_STD("aggregate_std");

  private final String wireName;

  ReportRule(String wireName) {
    this.wireName = wireName;
  }

  @Override
  public String wireName() {
    return wireName;
  }
}
