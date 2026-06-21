package com.the3Cgrp.zupptrade.agent2.engine.layer5;

import com.the3Cgrp.zupptrade.agent2.config.TradingConfig;
import com.the3Cgrp.zupptrade.agent2.domain.entity.UserProfileEntity;
import com.the3Cgrp.zupptrade.agent2.engine.RecommendationContext;
import com.the3Cgrp.zupptrade.agent2.engine.layer4.GateValidator;
import com.the3Cgrp.zupptrade.shared.dto.TradeLegDto;
import com.the3Cgrp.zupptrade.shared.enums.LegAction;
import com.the3Cgrp.zupptrade.shared.enums.OptionType;
import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSizerTest {

    private PositionSizer positionSizer;

    @BeforeEach
    void setUp() {
        TradingConfig config = new TradingConfig();
        GateValidator gateValidator = new GateValidator(config);
        positionSizer = new PositionSizer(gateValidator);
    }

    // Validated against worked example from context doc:
    // Spot: 23998, Short: 23500 PE @ 34.50, Long: 23450 PE @ 29.40
    // Net: 5.10, Spread: 50, Lot: 65, Capital: 500000
    // Max loss/lot = (50×65) - (5.10×65) = 3250 - 331.5 = 2918.5
    // Real loss/lot = 2918.5 × 0.5 = 1459.25
    // Max loss budget = 500000 × 1.5% = 7500
    // Lots = floor(7500 / 1459.25) = 5
    @Test
    void execute_workedExampleFromContextDoc_computesCorrectLots() {
        RecommendationContext ctx = buildContext(
                23500, new BigDecimal("34.50"),
                23450, new BigDecimal("29.40"),
                SpreadDirection.CREDIT, 65,
                new BigDecimal("500000"), new BigDecimal("1.5"), 2
        );

        positionSizer.execute(ctx);

        assertThat(ctx.getLots()).isEqualTo(5);
        assertThat(ctx.getMaxProfitTotal()).isEqualByComparingTo(new BigDecimal("1657.50")); // 5.10 × 65 × 5
    }

    @Test
    void execute_creditSpread_maxLossIsSpreadWidthMinusPremium() {
        RecommendationContext ctx = buildContext(
                23500, new BigDecimal("34.50"),
                23450, new BigDecimal("29.40"),
                SpreadDirection.CREDIT, 65,
                new BigDecimal("500000"), new BigDecimal("1.5"), 5
        );

        positionSizer.execute(ctx);

        // Max loss per lot = (50 × 65) - (5.10 × 65) = 2918.50
        // Real expected loss = 2918.50 × 0.5 × lots
        assertThat(ctx.getRealExpectedLossTotal()).isLessThanOrEqualTo(new BigDecimal("7500"));
    }

    @Test
    void execute_lotsIsAtLeastOne() {
        // Very high premium eating most of spread — real loss may be tiny
        RecommendationContext ctx = buildContext(
                23500, new BigDecimal("49.00"),
                23450, new BigDecimal("1.00"),
                SpreadDirection.CREDIT, 65,
                new BigDecimal("500000"), new BigDecimal("1.5"), 5
        );

        positionSizer.execute(ctx);

        assertThat(ctx.getLots()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void execute_rocCalculatedAsMaxProfitOverCapital() {
        RecommendationContext ctx = buildContext(
                23500, new BigDecimal("34.50"),
                23450, new BigDecimal("29.40"),
                SpreadDirection.CREDIT, 65,
                new BigDecimal("500000"), new BigDecimal("1.5"), 2
        );

        positionSizer.execute(ctx);

        // RoC = max_profit_total / capital × 100
        BigDecimal expectedRoc = ctx.getMaxProfitTotal()
                .divide(new BigDecimal("500000"), 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        assertThat(ctx.getRoc()).isEqualByComparingTo(expectedRoc.setScale(4, java.math.RoundingMode.HALF_UP));
    }

    private RecommendationContext buildContext(int shortStrike, BigDecimal shortLtp,
                                               int longStrike, BigDecimal longLtp,
                                               SpreadDirection direction, int lotSize,
                                               BigDecimal capital, BigDecimal maxLossPct, int dte) {
        RecommendationContext ctx = new RecommendationContext();
        ctx.setSpreadDirection(direction);
        ctx.setLotSize(lotSize);
        ctx.setDte(dte);
        ctx.setSpot(new BigDecimal("23998"));
        ctx.setGateResults(new ArrayList<>());

        TradeLegDto shortLeg = new TradeLegDto(OptionType.PE, shortStrike, shortLtp,
                LegAction.SELL, new BigDecimal("-0.15"), new BigDecimal("0.87"), null);
        TradeLegDto longLeg = new TradeLegDto(OptionType.PE, longStrike, longLtp,
                LegAction.BUY, new BigDecimal("-0.12"), new BigDecimal("0.90"), null);
        ctx.setShortLeg(shortLeg);
        ctx.setLongLeg(longLeg);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setCapital(capital);
        profile.setMaxLossPct(maxLossPct);
        profile.setMinPop(new BigDecimal("80"));
        profile.setMaxPopPoppGap(new BigDecimal("15"));
        profile.setSpreadWidthMin(50);
        profile.setSpreadWidthMax(100);
        ctx.setUserProfile(profile);

        return ctx;
    }
}
