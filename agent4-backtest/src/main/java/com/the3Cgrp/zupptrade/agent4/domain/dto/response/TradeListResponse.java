package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.time.LocalDate;
import java.util.List;

public record TradeListResponse(
        List<TradeListItemDto> trades,
        int page,
        int size,
        long totalCount,
        boolean hasMore,
        LocalDate periodFrom,
        LocalDate periodTo
) {}
