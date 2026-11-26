/**
 * The evaluation's scoring tool (docs/EVALUATION.md, "Scoring the detectors"): it builds probe
 * subsets from stored per-prompt counts, passes them through draftwatch's own detectors, and
 * writes every number the evaluation reports, each traced to a stored result file.
 */
package dev.draftwatch.scoring;
