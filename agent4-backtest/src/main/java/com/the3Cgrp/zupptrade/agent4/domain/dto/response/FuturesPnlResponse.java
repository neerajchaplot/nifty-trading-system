package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.util.List;

/** Agent 4 futures P&L filter response: summary counts + one row per closed futures trade. */
public record FuturesPnlResponse(
        FuturesPnlSummaryDto summary,
        List<FuturesPnlRowDto> trades
) {}
