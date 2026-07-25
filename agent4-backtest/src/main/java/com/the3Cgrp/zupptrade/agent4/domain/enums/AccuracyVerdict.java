package com.the3Cgrp.zupptrade.agent4.domain.enums;

/**
 * Price-based signal-accuracy verdict (Agent 4). A signal is graded purely on whether
 * Nifty moved from its scoring-time spot to its expiry-day close the way its bias+strength
 * promised — independent of any trade.
 *
 * TODO(PARTIAL): add a PARTIAL verdict for "direction right, magnitude short" once the
 * binary model is validated (e.g. Bullish Mild that rose but by less than mildPoints).
 */
public enum AccuracyVerdict {
    /** Net move met the signal's directional magnitude promise. */
    ACCURATE,
    /** Net move failed the signal's promise (wrong direction, or range broken for neutral/weak). */
    WRONG,
    /** Cannot be graded — signal spot or expiry-day close is missing. */
    NOT_MEASURED,
    /** Expiry has not yet passed — the outcome is not resolvable yet. Excluded from accuracy. */
    PENDING
}
