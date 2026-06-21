package com.the3Cgrp.zupptrade.agent3.engine;

import com.the3Cgrp.zupptrade.agent3.model.EvaluationResult;
import com.the3Cgrp.zupptrade.agent3.model.MonitorEvaluationContext;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

/**
 * Strategy Pattern — each trade type has its own evaluation logic.
 * Implementations: CreditSpreadMonitorStrategy, DebitSpreadMonitorStrategy, IronCondorMonitorStrategy.
 */
public interface MonitorStrategy {

    EvaluationResult evaluate(MonitorEvaluationContext context);

    /** Returns the strategy this implementation handles. */
    Strategy getStrategy();
}
