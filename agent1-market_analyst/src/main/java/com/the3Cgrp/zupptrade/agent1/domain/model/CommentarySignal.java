package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.util.List;

/**
 * Structured output from LLM commentary extraction (Tier 4).
 *
 * bias        — BULLISH / BEARISH / NEUTRAL  (feeds Tier 4 composite score)
 * conviction  — HIGH / MEDIUM / LOW          (feeds confidence modifier)
 * niftySupport    — key support levels mentioned in commentary, rounded to nearest 50
 * niftyResistance — key resistance levels mentioned in commentary, rounded to nearest 50
 * keyInsight  — one-sentence human-readable summary (stored in agent1_signals.key_levels
 *               JSONB for Agent 2 to read; not used in scoring)
 *
 * All bias/conviction values are uppercase to match shared-domain enums.
 * On any LLM failure → neutral() is returned; scoring pipeline continues unaffected.
 */
public record CommentarySignal(
        String bias,                    // "BULLISH" | "BEARISH" | "NEUTRAL"
        String conviction,              // "HIGH" | "MEDIUM" | "LOW"
        List<Integer> niftySupport,     // e.g. [23500, 23200]
        List<Integer> niftyResistance,  // e.g. [24000, 24200]
        String keyInsight               // e.g. "FIIs covering shorts, DII buying on dips"
) {
    public static CommentarySignal neutral() {
        return new CommentarySignal("NEUTRAL", "LOW", List.of(), List.of(), null);
    }
}
