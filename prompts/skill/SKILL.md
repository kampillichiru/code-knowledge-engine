---
name: functional-docs
description: Build the functional documentation for a Java codebase end to end (analysis, AI wording, missing-information questions, rebuild).
---

# Build functional documentation

You drive a fixed pipeline. The pipeline does the analysis and all checking; you do only two things: write plain-English replies from small
fact packs, and ask the user for information the code cannot give. Do not read the Java source yourself and do not invent anything.

Inputs: the Java source folder(s) (ask the user if not given), optionally the compiled classes folder, and an output folder (default `analysis-output`).
The jar is `dist/code-intelligence-engine.jar` (or `code-intelligence-engine.jar` next to this file).

## Steps

1. **Run the pipeline**
   `java -jar dist/code-intelligence-engine.jar pipeline --source <source> [--source <module2>] [--bytecode <classes>] --output <out>`
   Read `<out>/PIPELINE_STATUS.md`.
2. **Write the AI replies.** Read `<out>/ai/PROMPT.md` once. For each file in `<out>/ai/pack/` that has no file of the same name in
   `<out>/ai/responses/`, open only that pack and write `<out>/ai/responses/<same file name>` exactly as the prompt describes. Use only the facts in
   that pack. This includes every `field-*.json` pack (one per output field): do all of them, one at a time, so your context stays small.
3. **Run the pipeline again** (same command). It checks every paragraph. Read `<out>/ai/verification-report.md`: for each REJECTED paragraph, fix the
   reply (remove the number, label or guess the report names, or cite the right fact) and run again. Do not argue with the checker.
4. **Ask for missing information.** If `<out>/NEEDS_INPUT.md` exists, ask the user each question in it, one by one, quoting it. Write each answer as
   `key = answer` in `<out>/context/answers.properties` (for fixed values and names use `<out>/context/glossary.properties`). If the user does not know,
   leave the key out and say so. Then run the pipeline again.
5. **Repeat** steps 3 and 4 until `NEEDS_INPUT.md` is gone (or the user says to stop) and the report has no rejected paragraphs.
6. **Finish.** Tell the user where the documents are: `F01_Business_Overview.md` first, then `F02` (flow), `F03` (rules), `F04` (data dictionary),
   `F07` (one section per output field), `N01_AI_Narrative.md` (smoother prose), `N02_Field_Derivations.md` (each field explained end to end), `F06` (what is still open). Mention anything still unanswered. Never present AI wording as confirmed business
   meaning: only answers the user gave are confirmed.

## Rules
- Never edit the generated documents by hand; change `context/` or `ai/responses/` and rerun.
- Never put a number, name, reason or purpose in a reply that is not in the pack.
- If a command fails, show the error and stop; do not work around it.
