package com.the3Cgrp.zupptrade.agent2.engine.layer1;

import com.the3Cgrp.zupptrade.shared.enums.SpreadDirection;
import com.the3Cgrp.zupptrade.shared.enums.Strategy;

public record StrategySelection(Strategy strategy, SpreadDirection spreadDirection) {}
