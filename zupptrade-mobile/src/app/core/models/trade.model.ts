import { LegAction, MonitorAction, OptionType, SpreadDirection, Strategy, ThresholdHit, TradeStatus } from './enums';

export interface TradeLeg {
  optionType: OptionType;
  strike: number;
  ltp: number;
  action: LegAction;
  delta: number | null;
  pop: number | null;
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
  t1WatchNiftyLevel: number | null;
  t2ReadjustNiftyLevel: number | null;
  t3ExitNiftyLevel: number | null;
  t2LossThreshold: number;
  t3LossThreshold: number;
  t1WatchNiftyDown: number | null;
  t2ReadjustNiftyDown: number | null;
  t3ExitNiftyDown: number | null;
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
  shortLeg2: TradeLeg | null;
  longLeg2: TradeLeg | null;
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
  shortLeg2: TradeLeg | null;
  longLeg2: TradeLeg | null;
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

export interface ConfirmRequest {
  tradeId: string;
  action: 'CONFIRM' | 'REJECT';
  overrideLots?: number | null;
  overrideThresholds?: OverrideThresholds | null;
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

export interface ExecuteTradeRequest {
  tradeId: string;
  legs: LegOrderRequest[];
}

export interface LegOrderRequest {
  instrumentKey: string;
  optionType: string;
  strike: number;
  action: string;
  limitPrice: number;
  quantity: number;
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

export interface LegFill {
  orderId: string;
  instrumentKey: string;
  optionType: string;
  strike: number;
  action: string;
  quantityFilled: number;
  averageFillPrice: number;
}
