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
  t1WatchNiftyLevel: number;
  t2ReadjustNiftyLevel: number;
  t3ExitNiftyLevel: number;
  t2LossThreshold: number;
  t3LossThreshold: number;
}

export interface TradeCard {
  tradeId: string;
  strategy: Strategy;
  spreadDirection: SpreadDirection;
  expiryDate: string;
  dte: number;
  shortLeg: TradeLeg;
  longLeg: TradeLeg;
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

export interface ConfirmRequest {
  tradeId: string;
  action: 'CONFIRM' | 'REJECT';
  overrideLots?: number | null;
}
