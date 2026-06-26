package com.the3Cgrp.zupptrade.agent4.service;

import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeListItemDto;
import com.the3Cgrp.zupptrade.agent4.domain.dto.response.TradeListResponse;
import com.the3Cgrp.zupptrade.agent4.mapper.TradeListItemMapper;
import com.the3Cgrp.zupptrade.agent4.repository.AnalyticsTradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class TradeListService {

    private final AnalyticsTradeRepository repository;

    public TradeListService(AnalyticsTradeRepository repository) {
        this.repository = repository;
    }

    public TradeListResponse getTrades(LocalDate from, LocalDate to, int page, int size) {
        int offset = page * size;
        List<TradeListItemDto> trades = repository
                .findClosedTrades(from, to, offset, size)
                .stream()
                .map(TradeListItemMapper::fromRow)
                .toList();

        long total   = repository.countClosedTrades(from, to);
        boolean more = (long) offset + size < total;

        return new TradeListResponse(trades, page, size, total, more, from, to);
    }
}
