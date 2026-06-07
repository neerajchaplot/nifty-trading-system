package com.the3Cgrp.zupptrade.core.upstox.model.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstoxOptionGreeks(
        BigDecimal vega,
        BigDecimal theta,
        BigDecimal gamma,
        BigDecimal delta,
        BigDecimal iv
) {}
