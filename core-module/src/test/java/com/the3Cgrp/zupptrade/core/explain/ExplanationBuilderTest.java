package com.the3Cgrp.zupptrade.core.explain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationBuilderTest {

    @Test
    void capitalisesAndPunctuatesAndJoinsWithSpace() {
        String out = new ExplanationBuilder()
                .sentence("the signal is neutral")
                .sentence("confidence is low")
                .build();
        assertThat(out).isEqualTo("The signal is neutral. Confidence is low.");
    }

    @Test
    void skipsNullAndBlankClauses() {
        String out = new ExplanationBuilder()
                .sentence(null)
                .sentence("   ")
                .sentence("only this one")
                .build();
        assertThat(out).isEqualTo("Only this one.");
    }

    @Test
    void preservesExistingTerminalPunctuation() {
        String out = new ExplanationBuilder().sentence("no trade — VIX is extreme!").build();
        assertThat(out).isEqualTo("No trade — VIX is extreme!");
    }

    @Test
    void sentenceIf_appendsOnlyWhenTrue() {
        String out = new ExplanationBuilder()
                .sentence("base")
                .sentenceIf(false, "hidden caveat")
                .sentenceIf(true, "shown caveat")
                .build();
        assertThat(out).isEqualTo("Base. Shown caveat.");
    }

    @Test
    void isEmpty_trueUntilSomethingAdded() {
        ExplanationBuilder b = new ExplanationBuilder();
        assertThat(b.isEmpty()).isTrue();
        b.sentence("x");
        assertThat(b.isEmpty()).isFalse();
    }
}
