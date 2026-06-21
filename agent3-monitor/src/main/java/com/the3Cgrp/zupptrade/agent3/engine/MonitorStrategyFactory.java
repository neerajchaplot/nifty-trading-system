package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.shared.enums.Strategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Factory Pattern — resolves the correct MonitorStrategy for a given trade strategy type.
 * Credit spreads (BULL_PUT_SPREAD, BEAR_CALL_SPREAD) both route to CreditSpreadMonitorStrategy.
 * Debit spreads (BULL_CALL_SPREAD, BEAR_PUT_SPREAD) route to DebitSpreadMonitorStrategy.
 */
@Component
public class MonitorStrategyFactory {

    private final CreditSpreadMonitorStrategy creditStrategy;
    private final DebitSpreadMonitorStrategy debitStrategy;
    private final IronCondorMonitorStrategy ironCondorStrategy;

    public MonitorStrategyFactory(CreditSpreadMonitorStrategy creditStrategy,
                                   DebitSpreadMonitorStrategy debitStrategy,
                                   IronCondorMonitorStrategy ironCondorStrategy) {
        this.creditStrategy = creditStrategy;
        this.debitStrategy = debitStrategy;
        this.ironCondorStrategy = ironCondorStrategy;
    }

    /**
     * Returns the appropriate strategy implementation for the given trade strategy type.
     * @throws IllegalArgumentException if the strategy type is not monitored (e.g. SKIP, NO_TRADE)
     */
    public MonitorStrategy resolve(Strategy strategy) {
        return switch (strategy) {
            case BULL_PUT_SPREAD, BEAR_CALL_SPREAD -> creditStrategy;
            case BULL_CALL_SPREAD -> debitStrategy;
            case IRON_CONDOR, WIDE_IRON_CONDOR      -> ironCondorStrategy;
            case SHORT_STRADDLE                     -> creditStrategy;  // treat as credit spread
            default -> throw new IllegalArgumentException(
                    "No monitor strategy registered for: " + strategy);
        };
    }
}
