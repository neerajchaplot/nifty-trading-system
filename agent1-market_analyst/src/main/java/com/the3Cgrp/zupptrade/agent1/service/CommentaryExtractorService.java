package com.the3Cgrp.zupptrade.agent1.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.the3Cgrp.zupptrade.agent1.client.GroqCommentaryClient;
import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Extracts a structured CommentarySignal from user-provided market commentary text
 * using the Anthropic Claude API via direct RestClient (AnthropicCommentaryClient).
 *
 * Design rules (CLAUDE.md §14):
 *   - Any LLM failure (timeout, HTTP error, JSON parse error) → return neutral(), log, never throw
 *   - Never block the scoring pipeline
 *   - GROQ_API_KEY is an env variable, never hardcoded
 *
 * Pattern: Service handles prompt engineering + fallback.
 *          GroqCommentaryClient handles HTTP mechanics.
 */
@Service
public class CommentaryExtractorService {

    private static final Logger log = LoggerFactory.getLogger(CommentaryExtractorService.class);

    private static final Set<String> VALID_BIASES     = Set.of("BULLISH", "BEARISH", "NEUTRAL");
    private static final Set<String> VALID_CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    private static final String SYSTEM_PROMPT = """
            You are a financial market analyst assistant for an automated Nifty 50 options trading system.
            Extract a structured market signal from the provided commentary and news sentiment.
            Return ONLY valid JSON — no explanation, no markdown, no preamble, no trailing text.

            JSON schema (all fields required):
            {
              "isRelevant":      true | false,
              "bias":            "BULLISH" | "BEARISH" | "NEUTRAL",
              "conviction":      "HIGH" | "MEDIUM" | "LOW",
              "niftySupport":    [integer, ...],
              "niftyResistance": [integer, ...],
              "keyInsight":      "one-sentence summary, max 20 words"
            }

            RELEVANCE CHECK — evaluate this FIRST:
            Set "isRelevant": false if the input:
            - Is not about the Indian stock market or Nifty 50 (e.g. US stocks, crypto, forex, commodities)
            - Is gibberish, random characters, or clearly not market commentary
            - Contains no meaningful information about Indian market direction
            If isRelevant is false, you MUST return:
              bias=NEUTRAL, conviction=LOW, niftySupport=[], niftyResistance=[], keyInsight=null
            Do NOT attempt to extract or infer a signal from irrelevant input. When in doubt, set isRelevant=false.

            Rules (apply only when isRelevant is true):
            - bias and conviction must be UPPERCASE exactly as shown.
            - All Nifty levels must be integers rounded to the nearest 50.
              If a range is given, take the midpoint rounded to nearest 50.
            - If no support levels are explicitly mentioned, return an empty array [].
            - If no resistance levels are explicitly mentioned, return an empty array [].
            - keyInsight: one sentence, max 20 words, focusing on the dominant market driver.
            - Do NOT infer levels. Only include levels explicitly stated in the commentary.
            """;

    // Not injected — ObjectMapper is lightweight and has no Spring-managed state.
    // Creating it statically avoids a bean dependency on JacksonAutoConfiguration.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GroqCommentaryClient groqClient;

    public CommentaryExtractorService(GroqCommentaryClient groqClient) {
        this.groqClient = groqClient;
    }

    /**
     * Extracts a structured signal from commentary text + Marketaux sentiment context.
     *
     * @param commentary         user-provided market commentary (must not be null/blank)
     * @param marketauxSentiment average Marketaux entity sentiment for ^NSEI (may be null)
     * @return CommentarySignal — never null; returns neutral() on any failure
     */
    public CommentarySignal extract(String commentary, BigDecimal marketauxSentiment) {
        if (commentary == null || commentary.isBlank()) {
            log.warn("commentary.extractor.skipped reason=blank_input");
            return CommentarySignal.neutral();
        }

        String userPrompt = buildUserPrompt(commentary, marketauxSentiment);

        try {
            String rawJson = groqClient.complete(SYSTEM_PROMPT, userPrompt);
            return parseAndValidate(rawJson);

        } catch (GroqCommentaryClient.GroqClientException e) {
            log.error("commentary.extractor.api_error error={} — returning neutral", e.getMessage());
            return CommentarySignal.neutral();
        } catch (Exception e) {
            log.error("commentary.extractor.unexpected_error error={} — returning neutral", e.getMessage(), e);
            return CommentarySignal.neutral();
        }
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    private String buildUserPrompt(String commentary, BigDecimal marketauxSentiment) {
        StringBuilder sb = new StringBuilder();
        sb.append("Market commentary:\n").append(commentary.trim());

        if (marketauxSentiment != null) {
            sb.append("\n\nMarketaux ^NSEI average sentiment score: ").append(marketauxSentiment)
              .append(" (scale: -1.0 = very bearish, 0 = neutral, +1.0 = very bullish)");
        }

        sb.append("\n\nExtract the structured market signal as JSON.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON parser + validator
    // -------------------------------------------------------------------------

    private CommentarySignal parseAndValidate(String rawJson) throws Exception {
        // Strip potential markdown code fences Claude might add despite instructions
        String json = rawJson.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end   = json.lastIndexOf("```");
            if (end > start) {
                json = json.substring(start, end).strip();
            }
        }

        LlmSignalDto dto = OBJECT_MAPPER.readValue(json, LlmSignalDto.class);

        // Relevance gate: if LLM flagged the commentary as unrelated to Nifty/India markets,
        // return neutral immediately — do not attempt to score irrelevant or gibberish input.
        // null isRelevant (older response without the field) is treated as relevant.
        if (Boolean.FALSE.equals(dto.isRelevant())) {
            log.warn("commentary.extractor.irrelevant — input not related to Nifty/India market, scoring as neutral");
            return CommentarySignal.neutral();
        }

        // Validate mandatory enum values — fall back to neutral on bad data
        String bias = dto.bias() != null ? dto.bias().toUpperCase().strip() : null;
        if (!VALID_BIASES.contains(bias)) {
            log.warn("commentary.extractor.invalid_bias bias={} — defaulting to NEUTRAL", dto.bias());
            bias = "NEUTRAL";
        }

        String conviction = dto.conviction() != null ? dto.conviction().toUpperCase().strip() : null;
        if (!VALID_CONVICTIONS.contains(conviction)) {
            log.warn("commentary.extractor.invalid_conviction conviction={} — defaulting to LOW", dto.conviction());
            conviction = "LOW";
        }

        List<Integer> support    = dto.niftySupport()    != null ? dto.niftySupport()    : List.of();
        List<Integer> resistance = dto.niftyResistance() != null ? dto.niftyResistance() : List.of();
        String keyInsight        = dto.keyInsight();

        log.info("commentary.extractor.parsed bias={} conviction={} support={} resistance={} insight={}",
                bias, conviction, support, resistance, keyInsight);

        return new CommentarySignal(bias, conviction, support, resistance, keyInsight);
    }

    // -------------------------------------------------------------------------
    // Internal DTO — maps the LLM JSON response
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LlmSignalDto(
            Boolean isRelevant,         // null treated as true (backwards-compatible)
            String bias,
            String conviction,
            List<Integer> niftySupport,
            List<Integer> niftyResistance,
            String keyInsight
    ) {}
}
