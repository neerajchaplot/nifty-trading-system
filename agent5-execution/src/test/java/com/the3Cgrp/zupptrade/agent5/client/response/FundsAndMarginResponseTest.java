package com.the3Cgrp.zupptrade.agent5.client.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser test against the REAL Upstox /v2/user/get-funds-and-margin response shape.
 *
 * Regression guard for the bug where margin values were mapped flat (data.available_margin)
 * instead of nested under the segment key (data.equity.available_margin) — which made the
 * Capital Deployed widget read ZERO even on a funded account.
 */
class FundsAndMarginResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Verbatim from Upstox docs: values live under data.equity / data.commodity.
    private static final String UPSTOX_JSON = """
        {
          "status": "success",
          "data": {
            "equity": {
              "used_margin": 150000.50,
              "payin_amount": 200.0,
              "span_margin": 0.0,
              "adhoc_margin": 0.0,
              "notional_cash": 0.0,
              "available_margin": 15507.46,
              "exposure_margin": 0.0
            },
            "commodity": {
              "used_margin": 0,
              "available_margin": 0
            }
          }
        }
        """;

    @Test
    void parsesMarginFromEquitySegment_notFlatData() throws Exception {
        FundsAndMarginResponse resp = mapper.readValue(UPSTOX_JSON, FundsAndMarginResponse.class);

        assertThat(resp.isApiSuccess()).isTrue();
        assertThat(resp.availableMargin()).isEqualByComparingTo("15507.46"); // data.equity.available_margin
        assertThat(resp.usedMargin()).isEqualByComparingTo("150000.50");     // data.equity.used_margin
    }

    @Test
    void errorStatus_returnsZeroMargins() throws Exception {
        String json = """
            { "status": "error", "data": null }
            """;
        FundsAndMarginResponse resp = mapper.readValue(json, FundsAndMarginResponse.class);

        assertThat(resp.isApiSuccess()).isFalse();
        assertThat(resp.availableMargin()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.usedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingEquitySegment_returnsZeroMargins() throws Exception {
        // status success but no equity object → must not NPE, must return ZERO
        String json = """
            { "status": "success", "data": { "commodity": { "available_margin": 5 } } }
            """;
        FundsAndMarginResponse resp = mapper.readValue(json, FundsAndMarginResponse.class);

        assertThat(resp.isApiSuccess()).isFalse();
        assertThat(resp.availableMargin()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.usedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
