package com.the3Cgrp.zupptrade.agent5.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OrderTagBuilder — no Spring context required.
 *
 * Verifies the tag/correlationId format contract that the Upstox order API
 * depends on for trade identification and query-by-tag.
 */
class OrderTagBuilderTest {

    // UUID chosen so first-8 = "a1b2c3d4" → tag = "ZUPP_A1B2C3D4"
    private static final UUID TRADE_ID = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000001");

    @Test
    void tag_prefixIsZUPP() {
        assertThat(OrderTagBuilder.tag(TRADE_ID)).startsWith("ZUPP_");
    }

    @Test
    void tag_usesFirst8CharsOfUuidUppercase() {
        assertThat(OrderTagBuilder.tag(TRADE_ID)).isEqualTo("ZUPP_A1B2C3D4");
    }

    @Test
    void tag_isAtMost20Chars() {
        // Upstox hard limit on tag length
        assertThat(OrderTagBuilder.tag(TRADE_ID).length()).isLessThanOrEqualTo(20);
    }

    @Test
    void correlationId_appendsLegIndex() {
        assertThat(OrderTagBuilder.correlationId(TRADE_ID, 0)).isEqualTo("ZUPP_A1B2C3D4_L0");
        assertThat(OrderTagBuilder.correlationId(TRADE_ID, 1)).isEqualTo("ZUPP_A1B2C3D4_L1");
    }

    @Test
    void correlationId_isUniquePerLeg() {
        String leg0 = OrderTagBuilder.correlationId(TRADE_ID, 0);
        String leg1 = OrderTagBuilder.correlationId(TRADE_ID, 1);
        assertThat(leg0).isNotEqualTo(leg1);
    }

    @Test
    void exitTag_appendsXSuffix() {
        assertThat(OrderTagBuilder.exitTag(TRADE_ID)).isEqualTo("ZUPP_A1B2C3D4_X");
    }

    @Test
    void exitCorrelationId_appendsXAndLegIndex() {
        assertThat(OrderTagBuilder.exitCorrelationId(TRADE_ID, 0)).isEqualTo("ZUPP_A1B2C3D4_X_L0");
        assertThat(OrderTagBuilder.exitCorrelationId(TRADE_ID, 1)).isEqualTo("ZUPP_A1B2C3D4_X_L1");
    }

    @Test
    void differentTradeIds_produceDifferentTags() {
        UUID id1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        assertThat(OrderTagBuilder.tag(id1)).isNotEqualTo(OrderTagBuilder.tag(id2));
    }
}
