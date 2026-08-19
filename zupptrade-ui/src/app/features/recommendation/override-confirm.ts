/**
 * Pure decision for whether the manual-override panel may confirm a trade.
 *
 * The PoP ≥ 50% floor and the credit "Watch > Readjust > Exit" ladder ordering are CREDIT-only rules.
 * A debit spread (bull call / bear put) is gated at recommend time by R:R (G1D) and monitored via
 * breakeven / profit-book / loss-cut — its directional PoP at breakeven is legitimately below 50% — so
 * those two checks must NOT block a debit confirm. The margin check applies to both.
 *
 * Extracted as a pure function so it is unit-testable without an Angular TestBed.
 */
export interface OverrideConfirmState {
  /** true for bull call / bear put (spreadDirection === 'DEBIT'). */
  isDebit: boolean;
  /** Credit 50% PoP floor tripped (component `isPopBlocked`). Ignored for debit. */
  popBlocked: boolean;
  /** Credit ladder ordering / short-strike breach invalid (component threshold checks). Ignored for debit. */
  thresholdInvalid: boolean;
  /** User has run the margin check (a result is present). */
  marginChecked: boolean;
  /** The margin check reported sufficient funds. */
  marginSufficient: boolean;
}

export function canConfirmOverride(s: OverrideConfirmState): boolean {
  if (!s.isDebit) {
    if (s.popBlocked) return false;
    if (s.thresholdInvalid) return false;
  }
  if (!s.marginChecked) return false;
  return s.marginSufficient;
}
