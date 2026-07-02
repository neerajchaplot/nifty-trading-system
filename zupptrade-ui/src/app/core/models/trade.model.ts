import { LegAction, MonitorAction, OptionType, SpreadDirection, Strategy, ThresholdHit, TradeStatus } from './enums';

export interface TradeLeg {
  optionType: OptionType;
  strike: number;
  ltp: number;
  action: LegAction;
  delta: number | null;
  pop: number | null;           // Upstox buyer's PoP (0–1)
  instrumentKey: string | null;
}

export interface GateResult {
  gate: string;
  passed: boolean;
  description: string;
  value: number;
  threshold: number;
}

export interface MonitorThresholds {
  // 2-leg spreads (null for Iron Condor)
  t1WatchNiftyLevel: number | null;
  t2ReadjustNiftyLevel: number | null;
  t3ExitNiftyLevel: number | null;
  t2LossThreshold: number;
  t3LossThreshold: number;
  // Iron Condor — put side (Nifty falling toward PE short)
  t1WatchNiftyDown: number | null;
  t2ReadjustNiftyDown: number | null;
  t3ExitNiftyDown: number | null;
  // Iron Condor — call side (Nifty rising toward CE short)
  t1WatchNiftyUp: number | null;
  t2ReadjustNiftyUp: number | null;
  t3ExitNiftyUp: number | null;
}

export interface TradeCard {
  tradeId: string;
  strategy: Strategy;
  spreadDirection: SpreadDirection;
  expiryDate: string;
  dte: number;
  shortLeg: TradeLeg;
  longLeg: TradeLeg;
  shortLeg2: TradeLeg | null;    // Iron Condor CE SELL leg
  longLeg2: TradeLeg | null;     // Iron Condor CE BUY leg
  netPremiumPerUnit: number;
  lots: number;
  lotSize: number;
  maxProfitTotal: number;
  theoreticalMaxLossTotal: number;
  realExpectedLossTotal: number;
  pop: number;
  popp: number;
  popGap: number;
  roc: number;
  rocAnnualised: number;
  netDelta: number | null;
  gateResults: GateResult[];
  thresholds: MonitorThresholds;
  rationale: string | null;
  generatedAt: string;
  validUntil: string;
  status: TradeStatus;
}

export interface MonitorConfig {
  tradeId: string;
  strategy: Strategy;
  spreadDirection: SpreadDirection;
  shortLeg: TradeLeg;
  longLeg: TradeLeg;
  shortLeg2: TradeLeg | null;    // Iron Condor CE SELL leg (null for 2-leg spreads)
  longLeg2: TradeLeg | null;     // Iron Condor CE BUY leg (null for 2-leg spreads)
  actualNetPremiumPerUnit: number;
  lots: number;
  lotSize: number;
  maxProfitTotal: number;
  actualMaxLossTotal: number;
  slippageAlert: boolean;
  slippageAmount: number | null;
  thresholds: MonitorThresholds;
  expiryDate: string;
  dte: number;
}

export interface ActiveTrade {
  tradeId: string;
  tradeCode: string;
  status: TradeStatus;
  expiryDate: string;
  monitorConfig: MonitorConfig | null;
  // Latest evaluation snapshot
  lastAction: MonitorAction | null;
  lastThresholdHit: ThresholdHit | null;
  spotPrice: number | null;
  vixLevel: number | null;
  currentPop: number | null;
  markToMarketPnl: number | null;
  shortLegLtp: number | null;
  longLegLtp: number | null;
  lastEvaluatedAt: string | null;
}

export interface RecommendRequest {
  agent1SignalId: string;
  userProfileId: string;
  relaxedGate1PopPct?: number | null;
}

export interface OverrideThresholds {
  t1WatchNiftyLevel: number | null;
  t2ReadjustNiftyLevel: number | null;
  t3ExitNiftyLevel: number | null;
}

export interface OverrideParams {
  peShortStrike: number;
  peLongStrike: number;
  ceShortStrike: number | null;
  ceLongStrike: number | null;
  lots: number;
  peShortLtp: number;
  peLongLtp: number;
  ceShortLtp: number | null;
  ceLongLtp: number | null;
  peShortInstrumentKey: string;
  peLongInstrumentKey: string;
  ceShortInstrumentKey: string | null;
  ceLongInstrumentKey: string | null;
  netPremiumPerUnit: number;
  pop: number;
  maxProfitTotal: number;
  theoreticalMaxLossTotal: number;
  realExpectedLossTotal: number;
  roc: number;
}

export interface ConfirmRequest {
  tradeId: string;
  action: 'CONFIRM' | 'REJECT';
  overrideLots?: number | null;
  overrideThresholds?: OverrideThresholds | null;
  overrideParams?: OverrideParams | null;
}

export interface CalculateOverrideRequest {
  tradeId: string;
  peShortStrike: number;
  peLongStrike: number;
  ceShortStrike?: number | null;
  ceLongStrike?: number | null;
  lots: number;
}

export interface CalculateOverrideResult {
  peShortLtp: number;
  peLongLtp: number;
  ceShortLtp: number | null;
  ceLongLtp: number | null;
  peShortInstrumentKey: string;
  peLongInstrumentKey: string;
  ceShortInstrumentKey: string | null;
  ceLongInstrumentKey: string | null;
  netPremiumPerUnit: number;
  pop: number;
  maxProfitTotal: number;
  theoreticalMaxLossTotal: number;
  realExpectedLossTotal: number;
  roc: number;
  popBlocked: boolean;
  lossBlocked: boolean;
}

export interface MarginCheckRequest {
  tradeId: string;
  overrideLots?: number | null;
}

export interface MarginCheckResult {
  requiredMargin: number;
  availableMargin: number;
  sufficient: boolean;
  shortfall: number | null;
}

export interface LegOrderRequest {
  instrumentKey: string;
  optionType: string;
  strike: number;
  action: string;
  limitPrice: number;
  quantity: number;
}

export interface ExecuteTradeRequest {
  tradeId: string;
  legs: LegOrderRequest[];
}

export interface LegFill {
  orderId: string;
  instrumentKey: string;
  optionType: string;
  strike: number;
  action: string;
  quantityFilled: number;
  averageFillPrice: number;
}

export interface ExecuteTradeResponse {
  tradeId: string;
  executionStatus: 'ACTIVE' | 'REJECTED' | 'FAILED';
  fills: LegFill[] | null;
  actualNetPremiumPerUnit: number | null;
  expectedNetPremiumPerUnit: number | null;
  slippageAlert: boolean;
  slippageMessage: string | null;
  rejectionReason: string | null;
  executedAt: string | null;
}
