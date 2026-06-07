package com.the3Cgrp.zupptrade.agent2.client.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OptionChainData(
        BigDecimal spot,
        LocalDate expiryDate,
        List<StrikeData> calls,
        List<StrikeData> puts,
        int atmStrike,
        BigDecimal atmCallLtp,
        BigDecimal atmPutLtp
) {}
