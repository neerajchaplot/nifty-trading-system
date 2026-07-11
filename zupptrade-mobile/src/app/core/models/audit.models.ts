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

export interface SignalQuality {
  totalSignals: number;
  signalsLeadingToTrade: number;
  signalsSkipped: number;
  overallAccuracyPct: number;
  accuracyByConfidence: Record<string, number>;
  accuracyByBias: Record<string, number>;
  commentaryDivergenceImpact: unknown;
  mostFrequentDataGap: string | null;
  skipReasons: unknown;
}
