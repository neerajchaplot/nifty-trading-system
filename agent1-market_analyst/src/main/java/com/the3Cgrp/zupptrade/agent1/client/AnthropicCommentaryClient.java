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
 * Low-level HTTP client for the Anthropic Messages API.
 *
 * NOTE: Replaced by GeminiCommentaryClient (Google Gemini free tier — 1,500 req/day, no credit card).
 * This class is kept for reference only. Not registered as a Spring bean.
 * To re-enable: add @Component, restore anthropicRestClient bean in RestClientConfig,
 * and restore anthropic.api section in application.yml.
 *
 * On any HTTP or deserialization failure → throws AnthropicClientException.
 */
public class AnthropicCommentaryClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCommentaryClient.class);

    private static final String MESSAGES_PATH = "/v1/messages";

    private final RestClient anthropicRestClient;
    private final String model;
    private final int maxTokens;

    public AnthropicCommentaryClient(
            @Qualifier("anthropicRestClient") RestClient anthropicRestClient,
            @Value("${anthropic.api.model}") String model,
            @Value("${anthropic.api.max-tokens}") int maxTokens) {
        this.anthropicRestClient = anthropicRestClient;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Calls the Anthropic Claude API with the given system + user prompt.
     *
     * @param systemPrompt strict instructions (JSON-only output, schema definition)
     * @param userPrompt   the commentary text + marketaux context
     * @return raw text content from Claude's first content block
     * @throws AnthropicClientException on HTTP error or empty/null response
     */
    public String complete(String systemPrompt, String userPrompt) {
        MessagesRequest request = new MessagesRequest(
                model,
                maxTokens,
                systemPrompt,
                List.of(new Message("user", userPrompt))
        );

        log.debug("anthropic.request model={} max_tokens={}", model, maxTokens);

        MessagesResponse response;
        try {
            response = anthropicRestClient.post()
                    .uri(MESSAGES_PATH)
                    .body(request)
                    .retrieve()
                    .body(MessagesResponse.class);
        } catch (RestClientException e) {
            throw new AnthropicClientException("Anthropic API call failed: " + e.getMessage(), e);
        }

        if (response == null
                || response.content() == null
                || response.content().isEmpty()) {
            throw new AnthropicClientException("Anthropic API returned empty content");
        }

        ContentBlock firstBlock = response.content().get(0);
        if (firstBlock == null || firstBlock.text() == null || firstBlock.text().isBlank()) {
            throw new AnthropicClientException("Anthropic API returned blank content block");
        }

        log.debug("anthropic.response stop_reason={} input_tokens={} output_tokens={}",
                response.stopReason(),
                response.usage() != null ? response.usage().inputTokens() : "?",
                response.usage() != null ? response.usage().outputTokens() : "?");

        return firstBlock.text();
    }

    // -------------------------------------------------------------------------
    // Request model
    // -------------------------------------------------------------------------

    /**
     * POST /v1/messages request body.
     * system is top-level (not a message) per Anthropic API spec.
     */
    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages
    ) {}

    record Message(
            String role,    // "user" | "assistant"
            String content
    ) {}

    // -------------------------------------------------------------------------
    // Response model
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagesResponse(
            String id,
            List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            Usage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(
            String type,   // "text"
            String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens")  int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {}

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static class AnthropicClientException extends RuntimeException {
        public AnthropicClientException(String message) { super(message); }
        public AnthropicClientException(String message, Throwable cause) { super(message, cause); }
    }
}
