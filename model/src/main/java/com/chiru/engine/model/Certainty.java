package com.chiru.engine.model;

/** How a fact was obtained. Deterministic facts must never be mixed up with AI interpretations. */
public enum Certainty {
    /** Read directly from source, bytecode, SQL or configuration. */
    EXTRACTED,
    /** Derived by a deterministic rule from extracted facts, but not stated explicitly in the source. */
    INFERRED,
    /** Plain-language explanation produced by an LLM; never a basis for lineage. */
    AI_INTERPRETED
}
