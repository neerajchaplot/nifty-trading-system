package com.the3Cgrp.zupptrade.core.explain;

/**
 * Null-safe fluent assembler for the plain-English "why" explanations shown behind the UI help
 * icons. Shared by agent1 (signal) and agent2 (recommendation) so the prose mechanics —
 * capitalisation, spacing, terminal punctuation, skipping empty clauses — live in exactly one
 * place. Agent-specific vocabulary is supplied by the caller.
 *
 * <p>Pure — no Spring, no I/O. Deterministic: same inputs always produce the same string.
 */
public final class ExplanationBuilder {

    private final StringBuilder sb = new StringBuilder();

    /**
     * Appends {@code text} as a sentence: null/blank is ignored, the first letter is capitalised,
     * and a full stop is added if the text does not already end in terminal punctuation.
     */
    public ExplanationBuilder sentence(String text) {
        if (text == null) return this;
        String t = text.strip();
        if (t.isEmpty()) return this;
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        if (last != '.' && last != '!' && last != '?') {
            t = t + ".";
        }
        if (sb.length() > 0) sb.append(' ');
        sb.append(t);
        return this;
    }

    /** Appends {@code text} as a sentence only when {@code condition} is true. */
    public ExplanationBuilder sentenceIf(boolean condition, String text) {
        return condition ? sentence(text) : this;
    }

    public boolean isEmpty() {
        return sb.length() == 0;
    }

    public String build() {
        return sb.toString();
    }
}
