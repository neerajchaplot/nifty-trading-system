package com.the3Cgrp.zupptrade.core.expiry;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the expiry-roll cutoff: on expiry day the current expiry stays live until 15:30 IST,
 * then rolls to the next weekly. Regression for the bug where an evening re-score on expiry day
 * kept returning the just-settled date, tripping Agent 2's DTE=0 rejection.
 */
class ExpiryDateServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Two consecutive weekly Tuesday expiries.
    private static final LocalDate EXPIRY_1 = LocalDate.of(2026, 8, 11);
    private static final LocalDate EXPIRY_2 = LocalDate.of(2026, 8, 18);
    private static final List<LocalDate> EXPIRIES =
            List.of(LocalDate.of(2026, 8, 4), EXPIRY_1, EXPIRY_2);

    private static ZonedDateTime ist(LocalDate d, int hour, int minute) {
        return ZonedDateTime.of(d, LocalTime.of(hour, minute), IST);
    }

    @Test
    void onExpiryDay_beforeCutoff_returnsTodaysExpiry() {
        LocalDate result = ExpiryDateService.resolveNextExpiry(EXPIRIES, ist(EXPIRY_1, 10, 0));
        assertThat(result).isEqualTo(EXPIRY_1); // still the live contract until 15:30
    }

    @Test
    void onExpiryDay_atCutoff_returnsTodaysExpiry() {
        // exactly 15:30 is not "after" the cutoff — still live
        LocalDate result = ExpiryDateService.resolveNextExpiry(EXPIRIES, ist(EXPIRY_1, 15, 30));
        assertThat(result).isEqualTo(EXPIRY_1);
    }

    @Test
    void onExpiryDay_afterCutoff_rollsToNextWeek() {
        // 22:08 IST re-score — the reported bug: must roll to the next expiry, not return the settled one
        LocalDate result = ExpiryDateService.resolveNextExpiry(EXPIRIES, ist(EXPIRY_1, 22, 8));
        assertThat(result).isEqualTo(EXPIRY_2);
    }

    @Test
    void onNonExpiryDay_returnsNextUpcoming() {
        LocalDate wednesday = EXPIRY_1.plusDays(1); // 2026-08-12
        LocalDate result = ExpiryDateService.resolveNextExpiry(EXPIRIES, ist(wednesday, 9, 30));
        assertThat(result).isEqualTo(EXPIRY_2);
    }

    @Test
    void skipsPastExpiries() {
        LocalDate afterLast = EXPIRY_2.plusDays(3);
        LocalDate result = ExpiryDateService.resolveNextExpiry(EXPIRIES, ist(afterLast, 9, 30));
        assertThat(result).isNull(); // no upcoming expiry in the list
    }
}
