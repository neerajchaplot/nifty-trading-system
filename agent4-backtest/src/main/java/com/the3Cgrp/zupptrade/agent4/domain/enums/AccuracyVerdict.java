package com.the3Cgrp.zupptrade.agent4.domain.enums;

public enum AccuracyVerdict {
    /** Signal direction matched the trade outcome. */
    ACCURATE,
    /** Signal direction contradicted the trade outcome. */
    WRONG,
    /** Strategy was non-directional (IronCondor, Straddle, Strangle) — no verdict possible. */
    NOT_MEASURED
}
