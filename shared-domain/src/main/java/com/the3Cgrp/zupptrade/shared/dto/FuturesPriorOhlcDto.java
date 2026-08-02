package com.the3Cgrp.zupptrade.shared.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Prior completed session's OHLC used to derive the Camarilla levels. */
public record FuturesPriorOhlcDto(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close
) {}
