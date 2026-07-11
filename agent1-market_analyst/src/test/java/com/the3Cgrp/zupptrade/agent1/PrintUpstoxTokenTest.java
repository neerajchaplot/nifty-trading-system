package com.the3Cgrp.zupptrade.agent1;

import com.the3Cgrp.zupptrade.core.upstox.auth.UpstoxTokenHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prints the decrypted Upstox access token so it can be exported as
 * UPSTOX_ACCESS_TOKEN before running test-data/capture/capture_friday.sh.
 *
 * Tagged "token-print" ONLY — NOT tagged "integration".
 * Parent pom excludes both "integration" and "token-print" from normal mvn test runs.
 *
 * HOW TO RUN:
 *   mvn test -pl agent1-market_analyst -Dgroups=token-print "-Dexcluded.test.groups="
 *
 * Prerequisites:
 *   - TOKEN_ENCRYPTION_KEY env var must be set (same key used by upstox-auth module)
 *   - upstox-auth module must have run at least once (token written to api_tokens table)
 *   - application-local.yml must be present in src/main/resources (gitignored)
 *
 * DEV / LOCAL ONLY — never run in CI or shared environments.
 */
@Tag("token-print")
@SpringBootTest
@ActiveProfiles("local")
class PrintUpstoxTokenTest {

    @Autowired
    private UpstoxTokenHolder tokenHolder;

    @Test
    void printDecryptedTokenForCaptureFridaySh() {
        String token = tokenHolder.getToken();

        assertThat(token)
            .as("No token in UpstoxTokenHolder — ensure TOKEN_ENCRYPTION_KEY is set and upstox-auth has run")
            .isNotBlank()
            .hasSizeGreaterThan(50);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  UPSTOX TOKEN — for capture_friday.sh");
        System.out.println("  Copy the export line below, then run capture_friday.sh");
        System.out.println("  in the SAME terminal window.");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println();
        System.out.println("  export UPSTOX_ACCESS_TOKEN=" + token);
        System.out.println();
        System.out.println("  Token length : " + token.length());
        System.out.println("  Token prefix : " + token.substring(0, Math.min(12, token.length())) + "...");
        System.out.println();
        System.out.println("  Then run (set EXPIRY_DATE to the next Tuesday expiry):");
        System.out.println("    export EXPIRY_DATE=2026-07-07");
        System.out.println("    bash test-data/capture/capture_friday.sh");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
