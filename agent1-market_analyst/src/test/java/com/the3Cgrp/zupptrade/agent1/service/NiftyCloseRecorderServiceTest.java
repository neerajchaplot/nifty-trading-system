package com.the3Cgrp.zupptrade.agent1.service;

import com.the3Cgrp.zupptrade.agent1.domain.entity.NiftyDailyCloseEntity;
import com.the3Cgrp.zupptrade.agent1.domain.model.OhlcCandle;
import com.the3Cgrp.zupptrade.agent1.repository.NiftyDailyCloseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

class NiftyCloseRecorderServiceTest {

    private final NiftyDailyCloseRepository repository = mock(NiftyDailyCloseRepository.class);
    private final NiftyCloseRecorderService service = new NiftyCloseRecorderService(repository);

    private static OhlcCandle candle(LocalDate date, String close) {
        return new OhlcCandle(date, null, null, null,
                close == null ? null : new BigDecimal(close), 0L);
    }

    @Test
    void nullListRecordsNothing() {
        assertThat(service.record(null)).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void emptyListRecordsNothing() {
        assertThat(service.record(List.of())).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void skipsTodaysFormingCandle() {
        // Only settled (strictly-before-today) sessions are authoritative.
        LocalDate today = LocalDate.now();
        List<OhlcCandle> candles = List.of(
                candle(today.minusDays(2), "23400"),
                candle(today.minusDays(1), "23500"),
                candle(today,              "23555")   // forming — must be skipped
        );
        when(repository.findExistingDatesFrom(any())).thenReturn(Set.of());

        int written = service.record(candles);

        assertThat(written).isEqualTo(2);
        ArgumentCaptor<List<NiftyDailyCloseEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        Set<LocalDate> savedDates = captor.getValue().stream()
                .map(NiftyDailyCloseEntity::getTradeDate).collect(Collectors.toSet());
        assertThat(savedDates).containsExactlyInAnyOrder(today.minusDays(2), today.minusDays(1));
        assertThat(savedDates).doesNotContain(today);
    }

    @Test
    void insertsOnlyMissingDates() {
        LocalDate today = LocalDate.now();
        LocalDate d1 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);
        LocalDate d3 = today.minusDays(1);
        List<OhlcCandle> candles = List.of(
                candle(d1, "23400"), candle(d2, "23500"), candle(d3, "23600"));
        // d2 already stored → only d1 and d3 inserted
        when(repository.findExistingDatesFrom(any())).thenReturn(Set.of(d2));

        int written = service.record(candles);

        assertThat(written).isEqualTo(2);
        ArgumentCaptor<List<NiftyDailyCloseEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        Set<LocalDate> savedDates = captor.getValue().stream()
                .map(NiftyDailyCloseEntity::getTradeDate).collect(Collectors.toSet());
        assertThat(savedDates).containsExactlyInAnyOrder(d1, d3);
    }

    @Test
    void allDatesAlreadyPresentInsertsNothing() {
        LocalDate today = LocalDate.now();
        LocalDate d1 = today.minusDays(2);
        LocalDate d2 = today.minusDays(1);
        when(repository.findExistingDatesFrom(any())).thenReturn(Set.of(d1, d2));

        int written = service.record(List.of(candle(d1, "23400"), candle(d2, "23500")));

        assertThat(written).isZero();
        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    void candlesWithNullCloseAreSkipped() {
        LocalDate today = LocalDate.now();
        when(repository.findExistingDatesFrom(any())).thenReturn(Set.of());

        int written = service.record(List.of(
                candle(today.minusDays(1), null),
                candle(today.minusDays(2), "23400")));

        assertThat(written).isEqualTo(1);
    }

    @Test
    void repositoryFailureIsSwallowed() {
        // Best-effort: a DB failure must never propagate to the scoring pipeline.
        LocalDate today = LocalDate.now();
        when(repository.findExistingDatesFrom(any())).thenReturn(Set.of());
        when(repository.saveAll(anyIterable())).thenThrow(new RuntimeException("db down"));

        int written = service.record(List.of(candle(today.minusDays(1), "23500")));

        assertThat(written).isZero();  // no throw
    }
}
