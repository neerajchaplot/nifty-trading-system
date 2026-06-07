package com.the3Cgrp.zupptrade.agent1;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Replaced by GroqCommentaryConnectivityTest.
 * Gemini had free-tier quota issues — billing-enabled Google Cloud projects set limit to 0.
 * Groq offers 14,400 req/day free with no credit card — better fit for development.
 *
 * To re-enable Gemini: see Task #8 — create API key from a billing-free Google Cloud project.
 * Run Groq tests: mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration"
 *                          "-Dtest=GroqCommentaryConnectivityTest"
 */
@Disabled("Replaced by GroqCommentaryConnectivityTest — see Task #8 for Gemini re-enable steps")
class GeminiCommentaryConnectivityTest {

    @Test
    void placeholder() {
        // No-op — see GroqCommentaryConnectivityTest
    }
}
