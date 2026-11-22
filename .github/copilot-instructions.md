# Java Code Intelligence Engine: Copilot instructions

This repo is a pure-Java (Java 21, Maven) static-analysis engine that documents an unknown Java
codebase and traces every output field back to its source with evidence.

## Hard rules
- Java only. No Python, no Docker.
- Deterministic extraction first. An LLM may only *explain* facts already in the analysis model
  (`analysis-output/analysis-model.json`); it must never invent business meaning, tables, columns or links.
- Every claim needs evidence: file path and line range. If there is none, write "UNRESOLVED", not a guess.
- Never call a trace VERIFIED unless every link in it is VERIFIED. Use PARTIAL or UNRESOLVED otherwise.
- Do not infer a column mapping from matching variable names; require SQL, ORM or schema evidence.
- Label every explanation `AI_INTERPRETED`; label parser output `EXTRACTED`.
- Markdown docs are generated from the model, not from raw parser output.

## Layout
`model/` entities, evidence, trace status · `core/` config, JSON store, pipeline ·
later: discovery, source-spoon, bytecode-sootup, sql, rules, lineage, scenarios, docs-gen, validation, cli.

## Workflow
Work one phase at a time; explain intended changes first; run `mvn test`; update the README.
