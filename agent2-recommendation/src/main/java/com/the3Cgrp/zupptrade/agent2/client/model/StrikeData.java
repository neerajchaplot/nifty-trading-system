package com.the3Cgrp.zupptrade.agent2.client.model;

import com.the3Cgrp.zupptrade.shared.enums.OptionType;

import java.math.BigDecimal;

public record StrikeData(
        int strike,
        OptionType optionType,
        BigDecimal ltp,
        BigDecimal iv,
        BigDecimal delta,
        BigDecimal pop,
        BigDecimal openInterest,
        BigDecimal bid,
        BigDecimal ask,
        String instrumentKey   // Upstox instrument key — e.g. NFO_OPT|NIFTY|2026-06-10|24100|PE
) {}
