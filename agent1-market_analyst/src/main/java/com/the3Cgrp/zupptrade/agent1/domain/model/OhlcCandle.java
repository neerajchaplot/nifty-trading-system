package com.the3Cgrp.zupptrade.agent1.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Single daily OHLC bar from Upstox historical candle API. */
public record OhlcCandle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {}
