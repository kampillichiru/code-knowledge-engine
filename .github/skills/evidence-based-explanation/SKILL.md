---
name: evidence-based-explanation
description: Use when explaining extracted code facts (rules, lineage, SQL) in plain language. Enforces evidence, trace status and no invention.
---

# Evidence-based explanation

1. Start from the analysis model JSON. Every statement must trace to an entity or relationship in it.
2. Cite evidence as `path/File.java:start-end`.
3. State each link's status: VERIFIED, PARTIAL or UNRESOLVED. A chain is verified only if all links are.
4. Separate "Extracted fact" from "AI interpretation" in the output.
5. Missing link: write "UNRESOLVED: <what is missing>". Never bridge it with a plausible guess.
6. Same output field derived differently per scenario: document each scenario separately.
