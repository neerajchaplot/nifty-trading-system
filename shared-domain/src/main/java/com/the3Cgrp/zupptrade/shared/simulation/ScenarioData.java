package com.the3Cgrp.zupptrade.shared.simulation;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Typed rows read from a scenario folder's data files. Plain records — no Spring — so any
 * agent module can consume them under the simulation profile.
 */
public final class ScenarioData {

    private ScenarioData() {}

    /** One row of spot_vix.csv. */
    public record SpotVix(Instant time, BigDecimal spot, BigDecimal vix) {}

    /** One row of option_chain.csv. */
    public record Strike(LocalDate date, int strike, OptionType type,
                         BigDecimal ltp, BigDecimal iv, BigDecimal delta, long oi, BigDecimal pop) {}

    /** One row of candles.csv (Nifty daily OHLC). */
    public record Candle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}

    /** One row of fii_dii.csv (net values in Rs Crore). */
    public record FiiDii(LocalDate date, BigDecimal fiiFutNet, BigDecimal fiiOptNet,
                         BigDecimal diiNet, BigDecimal fiiLongRatio) {}
}
