package com.the3Cgrp.zupptrade.agent1;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

/**
 * Exploration test — calls Upstox Market Information APIs (launched May 2026)
 * to see what FII/DII and Participant OI data is available.
 *
 * Endpoints under test:
 *   GET /v2/market/fii  — FII activity (index futures, stock options, cash)
 *   GET /v2/market/dii  — DII activity (cash segment)
 *
 * Run with:
 *   mvn test -pl agent1-market_analyst "-Dexcluded.test.groups=" "-Dgroups=integration"
 *            "-Dtest=UpstoxFiiDiiExplorationTest"
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class UpstoxFiiDiiExplorationTest {

    @Autowired
    @Qualifier("upstoxRestClient")
    private RestClient upstoxRestClient;

    private static final String TODAY = LocalDate.now().toString();

    // -------------------------------------------------------------------------
    // FII Index Futures activity
    // -------------------------------------------------------------------------

    @Test
    void fii_indexFutures_rawResponse() {
        System.out.println("\n=== FII Index Futures (NSE_FO|INDEX_FUTURES) ===");
        System.out.println("Date: " + TODAY);

        try {
            String response = upstoxRestClient.get()
                    .uri(u -> u.path("/v2/market/fii")
                            .queryParam("data_type", "NSE_FO|INDEX_FUTURES")
                            .queryParam("interval", "1D")
                            .queryParam("_from", TODAY)
                            .build())
                    .retrieve()
                    .body(String.class);

            System.out.println("Response:\n" + response);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // FII Cash segment activity
    // -------------------------------------------------------------------------

    @Test
    void fii_cash_rawResponse() {
        System.out.println("\n=== FII Cash Segment (NSE_EQ|CASH) ===");
        System.out.println("Date: " + TODAY);

        try {
            String response = upstoxRestClient.get()
                    .uri(u -> u.path("/v2/market/fii")
                            .queryParam("data_type", "NSE_EQ|CASH")
                            .queryParam("interval", "1D")
                            .queryParam("_from", TODAY)
                            .build())
                    .retrieve()
                    .body(String.class);

            System.out.println("Response:\n" + response);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // DII Cash segment activity
    // -------------------------------------------------------------------------

    @Test
    void dii_cash_rawResponse() {
        System.out.println("\n=== DII Cash Segment (NSE_EQ|CASH) ===");
        System.out.println("Date: " + TODAY);

        try {
            String response = upstoxRestClient.get()
                    .uri(u -> u.path("/v2/market/dii")
                            .queryParam("data_type", "NSE_EQ|CASH")
                            .queryParam("interval", "1D")
                            .queryParam("_from", TODAY)
                            .build())
                    .retrieve()
                    .body(String.class);

            System.out.println("Response:\n" + response);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GIFT Nifty probe — try known instrument key candidates
    // -------------------------------------------------------------------------

    @Test
    void giftNifty_liveValue() {
        // Correct endpoint: v3 (not v2), exchange: GLOBAL_INDEX, name: SGX NIFTY (original name)
        // Source: https://api.upstox.com/v3/market-quote/ltp?instrument_key=GLOBAL_INDEX|SGX NIFTY
        System.out.println("\n=== GIFT Nifty Live Value (Upstox v3 — GLOBAL_INDEX|SGX NIFTY) ===");

        try {
            String response = upstoxRestClient.get()
                    .uri(u -> u.path("/v3/market-quote/ltp")
                            .queryParam("instrument_key", "GLOBAL_INDEX|SGX NIFTY")
                            .build())
                    .retrieve()
                    .body(String.class);
            System.out.println("Response: " + response);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // FII Stock Options activity
    // -------------------------------------------------------------------------

    @Test
    void fii_stockOptions_rawResponse() {
        System.out.println("\n=== FII Stock Options (NSE_FO|STOCK_OPTIONS) ===");
        System.out.println("Date: " + TODAY);

        try {
            String response = upstoxRestClient.get()
                    .uri(u -> u.path("/v2/market/fii")
                            .queryParam("data_type", "NSE_FO|STOCK_OPTIONS")
                            .queryParam("interval", "1D")
                            .queryParam("_from", TODAY)
                            .build())
                    .retrieve()
                    .body(String.class);

            System.out.println("Response:\n" + response);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }
}
