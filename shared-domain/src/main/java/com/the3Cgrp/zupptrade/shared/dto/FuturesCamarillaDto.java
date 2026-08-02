package com.the3Cgrp.zupptrade.shared.dto;

import java.math.BigDecimal;

/** Camarilla key levels for the trade card (spec §2.1). */
public record FuturesCamarillaDto(
        BigDecimal range,
        BigDecimal pivot,
        BigDecimal h3,
        BigDecimal h4,
        BigDecimal l3,
        BigDecimal l4
) {}
