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
 * Low-level HTTP client for the Groq Chat Completions API.
 *
 * Endpoint : POST /openai/v1/chat/completions
 * Auth     : Authorization: Bearer {GROQ_API_KEY}
 * Format   : OpenAI-compatible (messages array with role/content)
 *
 * Free tier (no credit card required):
 *   - 14,400 requests/day
 *   - 30 requests/minute
 *   - 500,000 tokens/day
 *   Get key at: https://console.groq.com → API Keys
 *
 * Model: llama-3.3-70b-versatile — capable, fast, excellent JSON output
 *
 * On any HTTP or deserialization failure → throws GroqClientException.
 * Callers (CommentaryExtractorService) catch and return neutral().
 */
@Component
public class GroqCommentaryClient {

    private static final Logger log = LoggerFactory.getLogger(GroqCommentaryClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/openai/v1/chat/completions";

    private final RestClient groqRestClient;
    private final String model;
    private final int maxTokens;

    public GroqCommentaryClient(
            @Qualifier("groqRestClient") RestClient groqRestClient,
            @Value("${groq.api.model}") String model,
            @Value("${groq.api.max-tokens}") int maxTokens) {
        this.groqRestClient = groqRestClient;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Calls Groq with the given system + user prompt.
     *
     * @param systemPrompt strict instructions (JSON-only output, schema definition)
     * @param userPrompt   the commentary text + marketaux context
     * @return raw text from choices[0].message.content
     * @throws GroqClientException on HTTP error or empty/null response
     */
    public String complete(String systemPrompt, String userPrompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(
                model,
                List.of(
                        new Message("system", systemPrompt),
                        new Message("user", userPrompt)
                ),
                maxTokens,
                0.1   // low temperature — deterministic JSON output
        );

        log.debug("groq.request model={} max_tokens={}", model, maxTokens);

        ChatCompletionResponse response;
        try {
            response = groqRestClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (RestClientException e) {
            throw new GroqClientException("Groq API call failed: " + e.getMessage(), e);
        }

        if (response == null
                || response.choices() == null
                || response.choices().isEmpty()) {
            throw new GroqClientException("Groq API returned empty choices");
        }

        Choice choice = response.choices().get(0);
        if (choice.message() == null || choice.message().content() == null
                || choice.message().content().isBlank()) {
            throw new GroqClientException("Groq API returned blank message content");
        }

        log.debug("groq.response finish_reason={} prompt_tokens={} completion_tokens={}",
                choice.finishReason(),
                response.usage() != null ? response.usage().promptTokens() : "?",
                response.usage() != null ? response.usage().completionTokens() : "?");

        return choice.message().content();
    }

    // -------------------------------------------------------------------------
    // Request models
    // -------------------------------------------------------------------------

    record ChatCompletionRequest(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature
    ) {}

    record Message(
            String role,     // "system" | "user" | "assistant"
            String content
    ) {}

    // -------------------------------------------------------------------------
    // Response models
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(
            String id,
            List<Choice> choices,
            Usage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens")     int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens")      int totalTokens
    ) {}

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static class GroqClientException extends RuntimeException {
        public GroqClientException(String message) { super(message); }
        public GroqClientException(String message, Throwable cause) { super(message, cause); }
    }
}
