package com.the3Cgrp.zupptrade.agent5.service;

import com.the3Cgrp.zupptrade.agent5.dto.ExecuteTradeRequest;
import com.the3Cgrp.zupptrade.agent5.dto.LegOrderRequest;
import com.the3Cgrp.zupptrade.shared.dto.GateResultDto;
import com.the3Cgrp.zupptrade.shared.dto.MonitorThresholdsDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeCardDto;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TradeExecutionRequestMapper.
 *
 * Verifies the Agent2 TradeCardDto → Agent5 ExecuteTradeRequest mapping.
 * This mapping is used by the Orchestrator immediately after Agent 2 /confirm.
 */
class TradeExecutionRequestMapperTest {

    private static final UUID        TRADE_ID     = UUID.randomUUID();
    private static final LocalDate   EXPIRY       = LocalDate.of(2026, 6, 24);
    private static final int         LOTS         = 3;
    private static final int         LOT_SIZE     = 75;
    private static final int         TOTAL_QTY    = LOTS * LOT_SIZE;   // 225

    private static final String SHORT_KEY = "NFO_OPT|NIFTY|2026-06-24|24500|PE";
    private static final String LONG_KEY  = "NFO_OPT|NIFTY|2026-06-24|24400|PE";

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void from_bullPutSpread_mapsShortAndLongLegsCorrectly() {
        TradeCardDto card = buildTradeCard();

        ExecuteTradeRequest request = TradeExecutionRequestMapper.from(card);

        assertThat(request.tradeId()).isEqualTo(TRADE_ID);
        assertThat(request.legs()).hasSize(2);

        LegOrderRequest shortLeg = request.legs().get(0);
        assertThat(shortLeg.instrumentKey()).isEqualTo(SHORT_KEY);
        assertThat(shortLeg.optionType()).isEqualTo(OptionType.PE);
        assertThat(shortLeg.strike()).isEqualTo(24500);
        assertThat(shortLeg.action()).isEqualTo(LegAction.SELL);
        assertThat(shortLeg.limitPrice()).isEqualByComparingTo(new BigDecimal("68.40"));
        assertThat(shortLeg.quantity()).isEqualTo(TOTAL_QTY);

        LegOrderRequest longLeg = request.legs().get(1);
        assertThat(longLeg.instrumentKey()).isEqualTo(LONG_KEY);
        assertThat(longLeg.optionType()).isEqualTo(OptionType.PE);
        assertThat(longLeg.strike()).isEqualTo(24400);
        assertThat(longLeg.action()).isEqualTo(LegAction.BUY);
        assertThat(longLeg.limitPrice()).isEqualByComparingTo(new BigDecimal("45.20"));
        assertThat(longLeg.quantity()).isEqualTo(TOTAL_QTY);
    }

    @Test
    void from_quantityIsLotsTimesLotSize() {
        TradeCardDto card = buildTradeCard();

        ExecuteTradeRequest request = TradeExecutionRequestMapper.from(card);

        // Both legs should have the same total quantity = lots × lotSize
        assertThat(request.legs()).allMatch(leg -> leg.quantity() == TOTAL_QTY);
    }

    @Test
    void from_preservesTradeId() {
        ExecuteTradeRequest request = TradeExecutionRequestMapper.from(buildTradeCard());
        assertThat(request.tradeId()).isEqualTo(TRADE_ID);
    }

    @Test
    void from_shortLegFirst_longLegSecond() {
        // Ordering matters: Agent5 places leg 0 as the primary leg
        ExecuteTradeRequest request = TradeExecutionRequestMapper.from(buildTradeCard());

        assertThat(request.legs().get(0).action()).isEqualTo(LegAction.SELL);  // short
        assertThat(request.legs().get(1).action()).isEqualTo(LegAction.BUY);   // long
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TradeCardDto buildTradeCard() {
        TradeLegDto shortLeg = new TradeLegDto(
                OptionType.PE, 24500, new BigDecimal("68.40"), LegAction.SELL,
                new BigDecimal("-0.169"), new BigDecimal("0.169"), SHORT_KEY);

        TradeLegDto longLeg = new TradeLegDto(
                OptionType.PE, 24400, new BigDecimal("45.20"), LegAction.BUY,
                new BigDecimal("-0.142"), new BigDecimal("0.142"), LONG_KEY);

        MonitorThresholdsDto thresholds = new MonitorThresholdsDto(
                new BigDecimal("24650"), new BigDecimal("24575"), new BigDecimal("-67138"),
                new BigDecimal("24500"), new BigDecimal("-134277"));

        return new TradeCardDto(
                TRADE_ID,
                Strategy.BULL_PUT_SPREAD,
                SpreadDirection.CREDIT,
                EXPIRY,
                3,
                shortLeg,
                longLeg,
                new BigDecimal("23.20"),
                LOTS,
                LOT_SIZE,
                new BigDecimal("81396"),
                new BigDecimal("268554"),
                new BigDecimal("134277"),
                new BigDecimal("82.6"),
                new BigDecimal("85.8"),
                new BigDecimal("3.2"),
                new BigDecimal("1.63"),
                new BigDecimal("78.1"),
                new BigDecimal("-0.027"),
                List.of(new GateResultDto("G1_POP", true, "Seller PoP ≥ 80%",
                        new BigDecimal("82.6"), new BigDecimal("80"))),
                thresholds,
                "BullPutSpread: VIX 18.6 Normal, IV Rich, strong bullish bias.",
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusMinutes(15),
                TradeStatus.CONFIRMED
        );
    }
}
