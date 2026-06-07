package com.the3Cgrp.zupptrade.agent1.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Low-level HTTP client for the Google Gemini generateContent API.
 *
 * NOTE: Replaced by GroqCommentaryClient (Groq free tier — 14,400 req/day, no credit card).
 * This class is kept for reference only. Not registered as a Spring bean.
 * To re-enable: add @Component, restore geminiRestClient bean in RestClientConfig,
 * restore gemini.api section in application.yml, and update CommentaryExtractorService.
 *
 * Free tier caveat: requires a Google Cloud project with NO billing account attached.
 * Keys from billing-enabled projects have free tier quota set to 0.
 */
public class GeminiCommentaryClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiCommentaryClient.class);

    private static final String GENERATE_PATH = "/v1beta/models/{model}:generateContent";

    private final RestClient geminiRestClient;
    private final String model;
    private final int maxOutputTokens;
    private final String apiKey;

    public GeminiCommentaryClient(
            @Qualifier("geminiRestClient") RestClient geminiRestClient,
            @Value("${gemini.api.model}") String model,
            @Value("${gemini.api.max-output-tokens}") int maxOutputTokens,
            @Value("${gemini.api.key}") String apiKey) {
        this.geminiRestClient = geminiRestClient;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.apiKey = apiKey;
    }

    /**
     * Calls Gemini with the given system + user prompt.
     *
     * @param systemPrompt strict instructions (JSON-only output, schema definition)
     * @param userPrompt   the commentary text + marketaux context
     * @return raw text from candidates[0].content.parts[0].text
     * @throws GeminiClientException on HTTP error or empty/null response
     */
    public String complete(String systemPrompt, String userPrompt) {
        GenerateContentRequest request = new GenerateContentRequest(
                new SystemInstruction(List.of(new Part(systemPrompt))),
                List.of(new Content("user", List.of(new Part(userPrompt)))),
                new GenerationConfig(maxOutputTokens, 0.1)
        );

        log.debug("gemini.request model={} max_output_tokens={}", model, maxOutputTokens);

        GenerateContentResponse response;
        try {
            response = geminiRestClient.post()
                    .uri(u -> u.path(GENERATE_PATH)
                               .queryParam("key", apiKey)
                               .build(model))
                    .body(request)
                    .retrieve()
                    .body(GenerateContentResponse.class);
        } catch (RestClientException e) {
            throw new GeminiClientException("Gemini API call failed: " + e.getMessage(), e);
        }

        if (response == null
                || response.candidates() == null
                || response.candidates().isEmpty()) {
            throw new GeminiClientException("Gemini API returned empty candidates");
        }

        Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            throw new GeminiClientException("Gemini API returned empty content parts");
        }

        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new GeminiClientException("Gemini API returned blank text");
        }

        log.debug("gemini.response finish_reason={} prompt_tokens={} output_tokens={}",
                candidate.finishReason(),
                response.usageMetadata() != null ? response.usageMetadata().promptTokenCount() : "?",
                response.usageMetadata() != null ? response.usageMetadata().candidatesTokenCount() : "?");

        return text;
    }

    // -------------------------------------------------------------------------
    // Request models
    // -------------------------------------------------------------------------

    record GenerateContentRequest(
            @JsonProperty("system_instruction") SystemInstruction systemInstruction,
            List<Content> contents,
            @JsonProperty("generationConfig") GenerationConfig generationConfig
    ) {}

    record SystemInstruction(List<Part> parts) {}

    record Content(String role, List<Part> parts) {}

    record Part(String text) {}

    record GenerationConfig(
            @JsonProperty("maxOutputTokens") int maxOutputTokens,
            double temperature
    ) {}

    // -------------------------------------------------------------------------
    // Response models
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateContentResponse(
            List<Candidate> candidates,
            @JsonProperty("usageMetadata") UsageMetadata usageMetadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(
            Content content,
            @JsonProperty("finishReason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(
            @JsonProperty("promptTokenCount") int promptTokenCount,
            @JsonProperty("candidatesTokenCount") int candidatesTokenCount
    ) {}

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static class GeminiClientException extends RuntimeException {
        public GeminiClientException(String message) { super(message); }
        public GeminiClientException(String message, Throwable cause) { super(message, cause); }
    }
}
