package com.the3Cgrp.zupptrade.shared.dto;

import java.math.BigDecimal;

public record GateResultDto(
        String gate,           // "G1", "G2", "G3", "G4"
        boolean passed,
        String description,    // human-readable rule description
        BigDecimal value,      // actual computed value
        BigDecimal threshold   // threshold that was checked against
) {}
