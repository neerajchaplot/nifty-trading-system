package com.the3Cgrp.zupptrade.agent2.client.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MarketSnapshot(
        BigDecimal spot,
        BigDecimal vix,
        LocalDateTime timestamp
) {}
