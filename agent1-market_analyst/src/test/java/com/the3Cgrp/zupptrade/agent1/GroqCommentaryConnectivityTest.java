package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.agent1.domain.model.CommentarySignal;
import com.the3Cgrp.zupptrade.agent1.service.CommentaryExtractorService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — calls the real Groq API (Llama 3.3 70B) with Nifty 50 commentary.
 * Verifies CommentaryExtractorService returns a valid structured CommentarySignal.
 *
 * Free tier: 14,400 req/day, 30 req/min — no credit card required.
 * Get key at: https://console.groq.com → API Keys (starts with gsk_...)
 *
 * HOW TO RUN (all tests):
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=GroqCommentaryConnectivityTest"
 *
 * HOW TO RUN (your own real commentary — Test 4):
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration" ^
 *            "-Dtest=GroqCommentaryConnectivityTest#extract_realCommentary_fromSystemProperty" ^
 *            "-Dtest.commentary=Paste Moneycontrol or ET market wrap text here" ^
 *            "-Dtest.marketaux.sentiment=0.15"
 *
 * Prerequisites:
 *   - GROQ_API_KEY environment variable set
 *   - Network access to api.groq.com
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class GroqCommentaryConnectivityTest {

    @Autowired
    private CommentaryExtractorService commentaryExtractor;

    private static final Set<String> VALID_BIASES      = Set.of("BULLISH", "BEARISH", "NEUTRAL");
    private static final Set<String> VALID_CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    // -------------------------------------------------------------------------
    // Test 1 — bullish commentary: FII buying, strong support, bullish RSI
    // -------------------------------------------------------------------------

    @Test
    void extract_bullishCommentary_returnsBullishSignalWithSupportLevels() {
        String commentary = """
                Nifty 50 opened gap-up and is holding above the 23,800 mark with strong buying
                interest from FIIs who net bought Rs 2,400 crore in index futures. The index has
                strong support at 23,500 and 23,200, with immediate resistance at 24,000 and 24,200.
                The overall trend remains bullish with DIIs also adding positions. RSI is at 62,
                indicating bullish momentum. Markets likely to test 24,000 resistance in near term.
                """;

        BigDecimal marketauxSentiment = new BigDecimal("0.45");

        System.out.println("\n=== Groq Commentary Extraction — Bullish Test ===");
        System.out.println("Model: llama-3.3-70b-versatile");
        System.out.println("Commentary length: " + commentary.trim().length() + " chars");

        CommentarySignal signal = commentaryExtractor.extract(commentary, marketauxSentiment);

        printSignal(signal);

        assertThat(signal).isNotNull();
        assertThat(signal.bias()).as("bias must be a valid enum value").isIn(VALID_BIASES);
        assertThat(signal.conviction()).as("conviction must be a valid enum value").isIn(VALID_CONVICTIONS);
        assertThat(signal.niftySupport()).as("niftySupport must not be null").isNotNull();
        assertThat(signal.niftyResistance()).as("niftyResistance must not be null").isNotNull();
        assertThat(signal.keyInsight()).as("keyInsight must not be blank").isNotBlank();

        assertThat(signal.bias())
                .as("Strongly bullish commentary should produce BULLISH bias")
                .isEqualTo("BULLISH");

        assertThat(signal.niftySupport())
                .as("23500 is explicitly mentioned as support — must be extracted")
                .contains(23500);

        assertThat(signal.niftyResistance())
                .as("24000 is explicitly mentioned as resistance — must be extracted")
                .contains(24000);

        System.out.println("\n✓ Groq API confirmed — bullish signal extracted correctly");
    }

    // -------------------------------------------------------------------------
    // Test 2 — bearish commentary: FII selling, breakdown, rising VIX
    // -------------------------------------------------------------------------

    @Test
    void extract_bearishCommentary_returnsBearishSignalWithResistanceLevels() {
        String commentary = """
                Nifty 50 broke below the critical 23,200 support zone on heavy volume with FIIs
                selling Rs 1,800 crore in index futures. The breakdown below 20 EMA is concerning.
                Immediate resistance is now at 23,500. Support at 23,000 and 22,800 if selling
                continues. Global cues are weak with US markets selling off. India VIX spiked to
                19.5 indicating rising fear. Traders should stay cautious and avoid fresh longs.
                """;

        BigDecimal marketauxSentiment = new BigDecimal("-0.42");

        System.out.println("\n=== Groq Commentary Extraction — Bearish Test ===");
        System.out.println("Commentary length: " + commentary.trim().length() + " chars");

        CommentarySignal signal = commentaryExtractor.extract(commentary, marketauxSentiment);

        printSignal(signal);

        assertThat(signal.bias()).isIn(VALID_BIASES);
        assertThat(signal.conviction()).isIn(VALID_CONVICTIONS);
        assertThat(signal.keyInsight()).isNotBlank();

        assertThat(signal.bias())
                .as("Strongly bearish commentary should produce BEARISH bias")
                .isEqualTo("BEARISH");

        assertThat(signal.niftyResistance())
                .as("23500 is explicitly mentioned as resistance after breakdown")
                .contains(23500);

        assertThat(signal.niftySupport())
                .as("23000 is explicitly mentioned as support")
                .contains(23000);

        System.out.println("\n✓ Bearish signal with correct levels extracted");
    }

    // -------------------------------------------------------------------------
    // Test 3 — neutral / range-bound commentary
    // -------------------------------------------------------------------------

    @Test
    void extract_neutralCommentary_returnsNeutralOrWeakDirectionalSignal() {
        String commentary = """
                Nifty 50 is trading in a narrow range between 23,400 and 23,700 with low volumes.
                Market participants are cautious ahead of RBI policy decision next week.
                FIIs were marginal sellers while DIIs provided support. Neither bulls nor bears
                are showing conviction. Support at 23,400 and resistance at 23,700.
                Options data shows max pain at 23,500 suggesting sideways movement.
                """;

        System.out.println("\n=== Groq Commentary Extraction — Neutral Test ===");

        CommentarySignal signal = commentaryExtractor.extract(commentary, null);

        printSignal(signal);

        assertThat(signal.bias()).isIn(VALID_BIASES);
        assertThat(signal.conviction()).isIn(VALID_CONVICTIONS);
        assertThat(signal.keyInsight()).isNotBlank();

        // Neutral commentary may return NEUTRAL or LOW conviction directional
        // — we don't assert exact bias, just that it's valid and levels are extracted
        assertThat(signal.niftySupport())
                .as("23400 is explicitly mentioned as support")
                .contains(23400);

        assertThat(signal.niftyResistance())
                .as("23700 is explicitly mentioned as resistance")
                .contains(23700);

        System.out.println("\n✓ Neutral commentary handled correctly");
    }

    // -------------------------------------------------------------------------
    // Test 4 — blank input must return neutral(), never throw
    // -------------------------------------------------------------------------

    @Test
    void extract_blankInput_returnsNeutral_pipelineNeverBlocked() {
        System.out.println("\n=== Groq Commentary Extraction — Blank Input Resilience ===");

        CommentarySignal signal = commentaryExtractor.extract("", null);

        assertThat(signal.bias()).isEqualTo("NEUTRAL");
        assertThat(signal.conviction()).isEqualTo("LOW");
        assertThat(signal.niftySupport()).isEmpty();
        assertThat(signal.niftyResistance()).isEmpty();

        System.out.println("✓ Blank input returned neutral() — scoring pipeline not blocked");
    }

    // -------------------------------------------------------------------------
    // Test 5 — real commentary from command line (-Dtest.commentary=...)
    // Only runs when explicitly provided — skipped in normal CI runs.
    //
    // Example:
    //   mvn test ... "-Dtest.commentary=<paste Moneycontrol/ET text here>"
    //                "-Dtest.marketaux.sentiment=0.12"
    // -------------------------------------------------------------------------

    @Test
    @EnabledIfSystemProperty(named = "test.commentary", matches = ".+")
    void extract_realCommentary_fromSystemProperty() {
        String commentary    = System.getProperty("test.commentary");
        String sentimentProp = System.getProperty("test.marketaux.sentiment");
        BigDecimal sentiment = sentimentProp != null ? new BigDecimal(sentimentProp) : null;

        System.out.println("\n=== Groq Commentary Extraction — Real Commentary ===");
        System.out.println("Commentary (" + commentary.length() + " chars):\n" + commentary);
        System.out.println("\nMarketaux sentiment: " + (sentiment != null ? sentiment : "not provided"));

        CommentarySignal signal = commentaryExtractor.extract(commentary, sentiment);

        System.out.println("\n--- Extracted Signal ---");
        printSignal(signal);

        // Only structural validation — no bias assertion since real commentary varies
        assertThat(signal.bias()).as("bias must be BULLISH, BEARISH or NEUTRAL").isIn(VALID_BIASES);
        assertThat(signal.conviction()).as("conviction must be HIGH, MEDIUM or LOW").isIn(VALID_CONVICTIONS);
        assertThat(signal.niftySupport()).isNotNull();
        assertThat(signal.niftyResistance()).isNotNull();

        System.out.println("\n✓ Real commentary extracted successfully");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void printSignal(CommentarySignal signal) {
        System.out.println("\nExtracted Signal:");
        System.out.println("  bias            : " + signal.bias());
        System.out.println("  conviction      : " + signal.conviction());
        System.out.println("  niftySupport    : " + signal.niftySupport());
        System.out.println("  niftyResistance : " + signal.niftyResistance());
        System.out.println("  keyInsight      : " + signal.keyInsight());
    }
}
