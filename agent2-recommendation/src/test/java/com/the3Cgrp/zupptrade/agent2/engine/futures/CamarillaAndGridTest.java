package com.the3Cgrp.zupptrade.agent2.engine.futures;

import com.the3Cgrp.zupptrade.agent2.engine.futures.model.CamarillaLevels;
import com.the3Cgrp.zupptrade.agent2.engine.futures.model.FuturesArm;
import com.the3Cgrp.zupptrade.shared.enums.FutureArmType;
import com.the3Cgrp.zupptrade.shared.enums.OpenZone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Camarilla, open classification, and the four-arm grid — anchored to the spec's
 * published 31-Jul levels (§5): H4 24396.68 · H3 24356.92 · pivot 24285.12 · L3 24277.39 · L4 24237.62.
 *
 * The prior-day OHLC that produces those levels (reverse-engineered from the formulas):
 *   H = 24341.39, L = 24196.81, C = 24317.16.
 */
class CamarillaAndGridTest {

    private final CamarillaCalculator camarilla = new CamarillaCalculator();
    private final OpenClassifier openClassifier = new OpenClassifier();
    private final FourArmGridBuilder gridBuilder = new FourArmGridBuilder();

    private static final BigDecimal H = new BigDecimal("24341.39");
    private static final BigDecimal L = new BigDecimal("24196.81");
    private static final BigDecimal C = new BigDecimal("24317.16");

    private CamarillaLevels levels() {
        return camarilla.calculate(H, L, C);
    }

    @Test
    void camarilla_matchesSpecPublished31JulLevels() {
        CamarillaLevels lv = levels();
        // H3, H4, pivot reproduce the spec exactly.
        assertThat(lv.h3()).isEqualByComparingTo("24356.92");
        assertThat(lv.h4()).isEqualByComparingTo("24396.68");
        assertThat(lv.pivot()).isEqualByComparingTo("24285.12");
        // L3, L4 within the spec's own rounding tolerance.
        assertThat(lv.l3().doubleValue()).isCloseTo(24277.39, within(0.05));
        assertThat(lv.l4().doubleValue()).isCloseTo(24237.62, within(0.05));
        assertThat(lv.range().doubleValue()).isCloseTo(144.58, within(0.05));
    }

    @Test
    void openClassifier_placesOpenAgainstBands() {
        CamarillaLevels lv = levels();
        assertThat(openClassifier.classify(new BigDecimal("24400"), lv)).isEqualTo(OpenZone.BREAKOUT);
        assertThat(openClassifier.classify(lv.h3(), lv)).isEqualTo(OpenZone.BREAKOUT);          // >= H3
        assertThat(openClassifier.classify(new BigDecimal("24300"), lv)).isEqualTo(OpenZone.RANGE);
        assertThat(openClassifier.classify(lv.l3(), lv)).isEqualTo(OpenZone.BREAKDOWN);         // <= L3
        assertThat(openClassifier.classify(new BigDecimal("24100"), lv)).isEqualTo(OpenZone.BREAKDOWN);
    }

    @Test
    void grid_mapsEachArmToCamarillaLevels() {
        CamarillaLevels lv = levels();
        Map<FutureArmType, FuturesArm> arms = gridBuilder.build(lv).stream()
                .collect(Collectors.toMap(FuturesArm::type, Function.identity()));

        FuturesArm longRot = arms.get(FutureArmType.LONG_ROTATION);
        assertThat(longRot.entry()).isEqualByComparingTo(lv.l3());   // reclaim L3
        assertThat(longRot.stop()).isEqualByComparingTo(lv.l4());    // below L4
        assertThat(longRot.target()).isEqualByComparingTo(lv.h3());  // → H3

        FuturesArm shortRot = arms.get(FutureArmType.SHORT_ROTATION);
        assertThat(shortRot.entry()).isEqualByComparingTo(lv.h3());  // reject H3
        assertThat(shortRot.stop()).isEqualByComparingTo(lv.h4());   // above H4
        assertThat(shortRot.target()).isEqualByComparingTo(lv.l3()); // → L3

        // Breakout entry clears H4 by the buffer (0.1 × H4-H3 gap ≈ 3.98) → ~24400.66;
        // spec worked example rounds this to 24401. Target rounds up to the next 50 → 24450.
        FuturesArm longBrk = arms.get(FutureArmType.LONG_BREAKOUT);
        assertThat(longBrk.entry().doubleValue()).isCloseTo(24400.66, within(0.05));
        assertThat(longBrk.stop()).isEqualByComparingTo(lv.h3());
        assertThat(longBrk.target()).isEqualByComparingTo("24450");

        FuturesArm shortBrk = arms.get(FutureArmType.SHORT_BREAKDOWN);
        assertThat(shortBrk.stop()).isEqualByComparingTo(lv.l3());
        assertThat(shortBrk.target()).isEqualByComparingTo("24200"); // next 50 below ~24233.66
    }
}
