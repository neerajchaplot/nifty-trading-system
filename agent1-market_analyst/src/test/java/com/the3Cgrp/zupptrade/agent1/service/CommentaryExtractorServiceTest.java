package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.client.GroqCommentaryClient;
import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CommentaryExtractorService.
 *
 * Covers the relevance guardrail: irrelevant or gibberish commentary must be
 * scored as neutral (zero Tier 4 contribution) without attempting signal extraction.
 */
@ExtendWith(MockitoExtension.class)
class CommentaryExtractorServiceTest {

    @Mock private GroqCommentaryClient groqClient;

    private CommentaryExtractorService service;

    @BeforeEach
    void setUp() {
        service = new CommentaryExtractorService(groqClient);
    }

    // ── Relevance guardrail ───────────────────────────────────────────────────

    @Test
    void irrelevantCommentary_llmSetsIsRelevantFalse_returnsNeutral() {
        // LLM correctly identifies that crypto commentary is not about Nifty
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"isRelevant":false,"bias":"NEUTRAL","conviction":"LOW",
                 "niftySupport":[],"niftyResistance":[],"keyInsight":null}
                """);

        CommentarySignal result = service.extract("Bitcoin is breaking ATH above $100k today!", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.conviction()).isEqualTo("LOW");
        assertThat(result.niftySupport()).isEmpty();
        assertThat(result.niftyResistance()).isEmpty();
        assertThat(result.keyInsight()).isNull();
    }

    @Test
    void gibberishCommentary_llmSetsIsRelevantFalse_returnsNeutral() {
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"isRelevant":false,"bias":"NEUTRAL","conviction":"LOW",
                 "niftySupport":[],"niftyResistance":[],"keyInsight":null}
                """);

        CommentarySignal result = service.extract("asdfgh qwerty zxcvbn 12345 !!!", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.conviction()).isEqualTo("LOW");
        // LLM must not be called more than once even for gibberish
        verify(groqClient, times(1)).complete(anyString(), anyString());
    }

    @Test
    void usStockCommentary_llmSetsIsRelevantFalse_returnsNeutral() {
        // US market commentary is irrelevant to Nifty 50 scoring
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"isRelevant":false,"bias":"NEUTRAL","conviction":"LOW",
                 "niftySupport":[],"niftyResistance":[],"keyInsight":null}
                """);

        CommentarySignal result = service.extract(
                "S&P 500 rallied 2% on Fed pivot signals. NASDAQ up 3%. Dow Jones hits new high.", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.conviction()).isEqualTo("LOW");
    }

    // ── Valid Nifty commentary ────────────────────────────────────────────────

    @Test
    void validBullishNiftyCommentary_isRelevantTrue_parsesBias() {
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"isRelevant":true,"bias":"BULLISH","conviction":"HIGH",
                 "niftySupport":[23500,23200],"niftyResistance":[24000],
                 "keyInsight":"FIIs net buyers as RBI holds rates steady"}
                """);

        CommentarySignal result = service.extract(
                "Nifty looks bullish. FIIs bought 3000Cr today. Support at 23500 and 23200. Resistance at 24000.",
                new BigDecimal("0.35"));

        assertThat(result.bias()).isEqualTo("BULLISH");
        assertThat(result.conviction()).isEqualTo("HIGH");
        assertThat(result.niftySupport()).containsExactly(23500, 23200);
        assertThat(result.niftyResistance()).containsExactly(24000);
        assertThat(result.keyInsight()).isEqualTo("FIIs net buyers as RBI holds rates steady");
    }

    @Test
    void validBearishNiftyCommentary_isRelevantTrue_parsesBias() {
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"isRelevant":true,"bias":"BEARISH","conviction":"MEDIUM",
                 "niftySupport":[],"niftyResistance":[23800],
                 "keyInsight":"Global cues weak, IT sector drag pulling index lower"}
                """);

        CommentarySignal result = service.extract(
                "Market weak. Nifty likely to test 23500. Resistance at 23800.", null);

        assertThat(result.bias()).isEqualTo("BEARISH");
        assertThat(result.conviction()).isEqualTo("MEDIUM");
    }

    // ── isRelevant field absent (backwards compatibility) ─────────────────────

    @Test
    void isRelevantFieldMissing_treatedAsRelevant_parsesBias() {
        // Older LLM response without the isRelevant field — must still parse correctly
        when(groqClient.complete(anyString(), anyString())).thenReturn("""
                {"bias":"BULLISH","conviction":"LOW",
                 "niftySupport":[],"niftyResistance":[],"keyInsight":"Cautiously bullish"}
                """);

        CommentarySignal result = service.extract("Nifty range-bound near 23600.", null);

        assertThat(result.bias()).isEqualTo("BULLISH");
        assertThat(result.conviction()).isEqualTo("LOW");
    }

    // ── Blank / null input ───────────────────────────────────────────────────

    @Test
    void blankCommentary_returnsNeutralWithoutCallingLlm() {
        CommentarySignal result = service.extract("   ", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.conviction()).isEqualTo("LOW");
        verifyNoInteractions(groqClient);
    }

    @Test
    void nullCommentary_returnsNeutralWithoutCallingLlm() {
        CommentarySignal result = service.extract(null, null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        verifyNoInteractions(groqClient);
    }

    // ── LLM failure resilience ───────────────────────────────────────────────

    @Test
    void llmApiError_returnsNeutralWithoutThrowing() {
        when(groqClient.complete(anyString(), anyString()))
                .thenThrow(new GroqCommentaryClient.GroqClientException("503 Service Unavailable"));

        CommentarySignal result = service.extract("Nifty looking strong today.", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.conviction()).isEqualTo("LOW");
    }

    @Test
    void llmReturnsMalformedJson_returnsNeutralWithoutThrowing() {
        when(groqClient.complete(anyString(), anyString())).thenReturn("not json at all %%");

        CommentarySignal result = service.extract("Nifty looking strong today.", null);

        assertThat(result.bias()).isEqualTo("NEUTRAL");
    }
}
