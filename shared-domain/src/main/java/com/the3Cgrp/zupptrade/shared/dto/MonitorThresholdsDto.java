package com.the3Cgrp.zupptrade.shared.dto;

import java.math.BigDecimal;

public record MonitorThresholdsDto(
        BigDecimal t1WatchNiftyLevel,    // Nifty level that triggers T1 watch
        BigDecimal t2ReadjustNiftyLevel, // Nifty level that triggers T2 readjust
        BigDecimal t3ExitNiftyLevel,     // Nifty level that triggers T3 exit (= short strike)
        BigDecimal t2LossThreshold,      // 50% of max loss in Rs — triggers T2 readjust
        BigDecimal t3LossThreshold       // 100% of max loss in Rs — triggers T3 exit
) {}
