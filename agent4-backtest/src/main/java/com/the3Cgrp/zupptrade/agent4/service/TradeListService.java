package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeListItemDto;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeListResponse;
import com.the3Cgrp.zupptrade.agent4.mapper.TradeListItemMapper;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import com.the3Cgrp.zupptrade.core.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class TradeListService {

    private final AnalyticsTradeRepository repository;
    private final OwnershipGuard guard;

    public TradeListService(AnalyticsTradeRepository repository, OwnershipGuard guard) {
        this.repository = repository;
        this.guard = guard;
    }

    public TradeListResponse getTrades(LocalDate from, LocalDate to, int page, int size) {
        // Phase 5 scope: caller's profile id, or null for admin (all users). 401 if anonymous.
        UUID scope = guard.scopeProfileId();
        int offset = page * size;

        List<TradeListItemDto> trades = repository
                .findClosedTrades(from, to, scope, offset, size)
                .stream()
                .map(TradeListItemMapper::fromRow)
                .toList();

        long total   = repository.countClosedTrades(from, to, scope);
        boolean more = (long) offset + size < total;

        // Corrupted trades: not paginated — always returned in full as a separate section.
        // Excluded from total count and hasMore so clients know these are outside the main list.
        List<TradeListItemDto> corrupted = repository
                .findCorruptedTrades(from, to, scope)
                .stream()
                .map(TradeListItemMapper::fromRow)
                .toList();

        return new TradeListResponse(trades, page, size, total, more, from, to, corrupted);
    }
}
