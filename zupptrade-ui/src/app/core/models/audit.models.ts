// ── TypeScript interfaces for Agent 4 (Audit) API responses ──────────────────

export interface PortfolioSummary {
  totalTrades: number;
  winCount: number;
  lossCount: number;
  winRatePct: number;
  totalRealizedPnl: number;
  maxLossSingleTrade: number;
  maxDrawdown: number;
  avgRocAchievedPct: number;
  avgRocTheoreticalPct: number;
  rocCaptureRatioPct: number;
  totalAdjustments: number;
  adjustmentRecoveryRatePct: number;
  agent1AccuracyPct: number;
  strategyMix: Record<string, number>;
  winRateByVixRegime: Record<string, number>;
  winRateByConfidence: Record<string, number>;
}

export type TradeOutcome = 'WIN' | 'LOSS' | 'OPEN';

export interface TradeListItem {
  tradeId: string;
  tradeCode: string;
  expiryDate: string;
  strategy: string;
  spreadDirection: string;
  signalBias: string;
  signalStrength: string;
  signalScore: number;
  signalConfidence: number;
  signalConfidenceLabel: string;
  entryDate: string;
  exitDate: string | null;
  holdingDays: number | null;
  lots: number;
  entryNetPremium: number;
  entrySpot: number | null;
  entryVixRegime: string | null;
  actualPnl: number;
  rocAchievedPct: number | null;
  adjustmentCount: number;
  closeReason: string | null;
  outcome: TradeOutcome;
}

export interface TradeListResponse {
  trades: TradeListItem[];
  page: number;
  size: number;
  totalCount: number;
  hasMore: boolean;
  periodFrom: string | null;
  periodTo: string | null;
}

// ── Audit chapters ─────────────────────────────────────────────────────────────

export interface SignalChapter {
  signalId: string;
  scoredAt: string;
  bias: string;
  strength: string;
  compositeScore: number;
  confidenceScore: number;
  confidenceLabel: string;
  vixLevel: number | null;
  vixRegime: string | null;
  vixDirection: string | null;
  commentaryDivergence: boolean;
  tierScores: Record<string, number>;
  dataGaps: string[];
}

export interface TradeLeg {
  action: 'BUY' | 'SELL';
  strike: number;
  type: 'CE' | 'PE';
  ltp: number;
  iv: number;
  delta: number;
  theta: number;
  vega: number;
  pop: number;
}

export interface RecommendationChapter {
  strategy: string;
  spreadDirection: string;
  legs: TradeLeg[];
  pop: number | null;
  popp: number | null;
  popGap: number | null;
  lots: number;
  lotSize: number;
  netPremiumPerUnit: number | null;
  maxProfitTotal: number | null;
  realExpectedLossTotal: number | null;
  rocTheoreticalPct: number | null;
  rocAnnualised: number | null;
  gateResults: Record<string, string>;
  t1WatchNifty: number | null;
  t2ReadjustNifty: number | null;
  t2ReadjustPnlLoss: number | null;
  t3ExitNifty: number | null;
  t3ExitPnlLoss: number | null;
}

export interface EntryExecution {
  placedAt: string | null;
  executedAt: string | null;
  requestedNetPremium: number | null;
  actualNetPremium: number | null;
  slippageAmount: number | null;
  slippagePct: number | null;
  requestedLots: number;
  filledLots: number;
  brokerStatus: string | null;
}

export interface ExitExecution {
  placedAt: string | null;
  executedAt: string | null;
  actualNetPremium: number | null;
  filledLots: number;
  brokerStatus: string | null;
  failureReason: string | null;
}

export interface ExecutionChapter {
  entry: EntryExecution | null;
  exit: ExitExecution | null;
  totalSlippage: number;
}

export interface MonitoringEvent {
  evaluatedAt: string;
  action: string;
  spotPrice: number | null;
  vixLevel: number | null;
  markToMarketPnl: number | null;
  currentPop: number | null;
  thresholdHit: string | null;
  reason: string | null;
}

export interface MonitoringChapter {
  events: MonitoringEvent[];
  holdCount: number;
  watchCount: number;
  readjustCount: number;
  exitTriggeredBy: string | null;
  finalPnl: number | null;
}

export interface TradeAudit {
  tradeId: string;
  tradeCode: string;
  status: string;
  expiryDate: string;
  outcome: TradeOutcome;
  actualPnl: number | null;
  closeReason: string | null;
  signal: SignalChapter;
  recommendation: RecommendationChapter;
  execution: ExecutionChapter;
  monitoring: MonitoringChapter;
}

export interface SignalQuality {
  totalSignals: number;
  signalsLeadingToTrade: number;
  signalsSkipped: number;
  overallAccuracyPct: number;
  accuracyByConfidence: Record<string, number>;
  accuracyByBias: Record<string, number>;
  commentaryDivergenceImpact: string | null;
  mostFrequentDataGap: string | null;
  skipReasons: Record<string, number>;
}
