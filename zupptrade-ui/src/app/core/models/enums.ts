export type Bias = 'BULLISH' | 'BEARISH' | 'NEUTRAL';
export type Strength = 'EXTREME' | 'MILD' | 'WEAK';
export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH';
export type VixRegime = 'LOW' | 'NORMAL' | 'HIGH' | 'EXTREME';
export type IvRegime = 'RICH' | 'FAIR' | 'CHEAP';
export type Strategy =
  | 'BULL_PUT_SPREAD' | 'BEAR_CALL_SPREAD' | 'BULL_CALL_SPREAD'
  | 'BEAR_PUT_SPREAD' | 'IRON_CONDOR' | 'SHORT_STRADDLE'
  | 'SHORT_STRANGLE' | 'SKIP';
export type SpreadDirection = 'CREDIT' | 'DEBIT';
export type TradeStatus =
  | 'PENDING_CONFIRM' | 'CONFIRMED' | 'REJECTED' | 'EXPIRED'
  | 'ACTIVE' | 'CLOSED' | 'EXIT_IN_PROGRESS' | 'EXIT_FAILED';
export type OptionType = 'CE' | 'PE';
export type LegAction = 'BUY' | 'SELL';
export type MonitorAction = 'HOLD' | 'WATCH' | 'READJUST' | 'EXIT';
export type ThresholdHit =
  | 'T1' | 'T2' | 'T3' | 'NONE'
  | 'DEBIT_POP_DISASTER' | 'DEBIT_GIVEBACK_LOCK';
export type ConfirmAction = 'CONFIRM' | 'REJECT';

// ── Nifty futures engine (ZUPPTRADE_FUTURES_TRADING_SPEC) ──
export type FutureArmType = 'LONG_ROTATION' | 'SHORT_ROTATION' | 'LONG_BREAKOUT' | 'SHORT_BREAKDOWN';
export type TradeDirection = 'LONG' | 'SHORT';
export type OpenZone = 'BREAKOUT' | 'RANGE' | 'BREAKDOWN';
export type ArmCardStatus = 'RECOMMENDED' | 'ALLOWED' | 'BLOCKED';
export type FuturePlanStatus =
  | 'PRIMED' | 'ARMED' | 'BREAK_DETECTED' | 'CONFIRMED' | 'FILLED'
  | 'CLOSED' | 'NO_TRADE' | 'REJECTED' | 'INVALIDATED' | 'EXPIRED' | 'EXECUTION_FAILED';
