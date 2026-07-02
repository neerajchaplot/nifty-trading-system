package com.the3Cgrp.zupptrade.agent4.domain.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * Response for the trade list API.
 *
 * {@code trades} contains paginated CLOSED trades included in all aggregations.
 * {@code corruptedTrades} contains CORRUPTED_MANUALLY trades as a separate section —
 * never included in summaries, win rates, or P&amp;L totals. Shown as a distinct list
 * so the user knows about partially-closed positions that need manual cleanup.
 */
public record TradeListResponse(
        List<TradeListItemDto> trades,
        int page,
        int size,
        long totalCount,
        boolean hasMore,
        LocalDate periodFrom,
        LocalDate periodTo,
        List<TradeListItemDto> corruptedTrades   // separate section — excluded from all aggregations
) {}
