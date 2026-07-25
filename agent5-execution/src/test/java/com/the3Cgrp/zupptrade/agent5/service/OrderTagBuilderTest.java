package com.the3Cgrp.zupptrade.agent5.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OrderTagBuilder — no Spring context required.
 *
 * V3 order APIs have no correlation_id; the per-leg tag does that job. Verifies the tag format
 * contract (unique per leg, ≤ 40 chars, ZUPP_ prefix) the Upstox v3 order API depends on.
 */
class OrderTagBuilderTest {

    // UUID chosen so first-8 = "a1b2c3d4" → base = "ZUPP_A1B2C3D4"
    private static final UUID TRADE_ID = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000001");

    @Test
    void entryTag_prefixAndLegIndex() {
        assertThat(OrderTagBuilder.entryTag(TRADE_ID, 0)).isEqualTo("ZUPP_A1B2C3D4_L0");
        assertThat(OrderTagBuilder.entryTag(TRADE_ID, 1)).isEqualTo("ZUPP_A1B2C3D4_L1");
    }

    @Test
    void entryTag_isUniquePerLeg() {
        assertThat(OrderTagBuilder.entryTag(TRADE_ID, 0))
                .isNotEqualTo(OrderTagBuilder.entryTag(TRADE_ID, 1));
    }

    @Test
    void exitTag_appendsXAndLegIndex() {
        assertThat(OrderTagBuilder.exitTag(TRADE_ID, 0)).isEqualTo("ZUPP_A1B2C3D4_X_L0");
        assertThat(OrderTagBuilder.exitTag(TRADE_ID, 1)).isEqualTo("ZUPP_A1B2C3D4_X_L1");
    }

    @Test
    void rollbackTag_appendsRbAndSeq() {
        assertThat(OrderTagBuilder.rollbackTag(TRADE_ID, 0)).isEqualTo("ZUPP_A1B2C3D4_RB_L0");
    }

    @Test
    void allTags_withinUpstoxV3Limit_40Chars() {
        assertThat(OrderTagBuilder.entryTag(TRADE_ID, 9).length()).isLessThanOrEqualTo(40);
        assertThat(OrderTagBuilder.exitTag(TRADE_ID, 9).length()).isLessThanOrEqualTo(40);
        assertThat(OrderTagBuilder.rollbackTag(TRADE_ID, 9).length()).isLessThanOrEqualTo(40);
    }

    @Test
    void differentTradeIds_produceDifferentTags() {
        UUID id1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        assertThat(OrderTagBuilder.entryTag(id1, 0)).isNotEqualTo(OrderTagBuilder.entryTag(id2, 0));
    }
}
