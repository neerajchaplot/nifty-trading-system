import { canConfirmOverride, OverrideConfirmState } from './override-confirm';

/**
 * Direction-aware override-confirm gating.
 *
 * Regression for the bug where a debit (bear put / bull call) trade could never be confirmed in the
 * override panel: the credit-only PoP≥50% floor and the Watch/Readjust/Exit ladder ordering always
 * tripped, permanently disabling Confirm. Debit must skip both; margin still applies to both.
 */
describe('canConfirmOverride', () => {

  const base: OverrideConfirmState = {
    isDebit: false,
    popBlocked: false,
    thresholdInvalid: false,
    marginChecked: true,
    marginSufficient: true,
  };

  // ── Credit: the existing hard blocks still apply ──────────────────────────────
  it('credit: all clear + margin sufficient → can confirm', () => {
    expect(canConfirmOverride(base)).toBe(true);
  });

  it('credit: PoP below 50% → blocked', () => {
    expect(canConfirmOverride({ ...base, popBlocked: true })).toBe(false);
  });

  it('credit: invalid threshold ladder → blocked', () => {
    expect(canConfirmOverride({ ...base, thresholdInvalid: true })).toBe(false);
  });

  // ── Debit: the credit-only blocks are ignored ─────────────────────────────────
  it('debit: PoP below 50% does NOT block (breakeven PoP is legitimately < 50%)', () => {
    expect(canConfirmOverride({ ...base, isDebit: true, popBlocked: true })).toBe(true);
  });

  it('debit: credit ladder ordering is irrelevant → not blocked', () => {
    expect(canConfirmOverride({ ...base, isDebit: true, thresholdInvalid: true })).toBe(true);
  });

  it('debit: both credit blocks set → still confirmable with sufficient margin', () => {
    expect(canConfirmOverride({
      ...base, isDebit: true, popBlocked: true, thresholdInvalid: true,
    })).toBe(true);
  });

  // ── Margin applies to both directions ─────────────────────────────────────────
  it('debit: margin not yet checked → blocked', () => {
    expect(canConfirmOverride({ ...base, isDebit: true, popBlocked: true, marginChecked: false })).toBe(false);
  });

  it('debit: margin insufficient → blocked', () => {
    expect(canConfirmOverride({ ...base, isDebit: true, marginChecked: true, marginSufficient: false })).toBe(false);
  });

  it('credit: margin not checked → blocked even when everything else is clear', () => {
    expect(canConfirmOverride({ ...base, marginChecked: false })).toBe(false);
  });
});
