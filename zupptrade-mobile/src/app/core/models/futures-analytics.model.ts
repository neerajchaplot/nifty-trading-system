import { Bias, Confidence, FutureArmType, TradeDirection } from './enums';

export interface FuturesPnlSummary {
  tradeCount: number;
  winCount: number;
  lossCount: number;
  totalRealizedPnl: number;
}

export interface FuturesPnlRow {
  planCode: string;
  tradeDate: string;
  armType: FutureArmType;
  direction: TradeDirection;
  entry: number;
  stop: number;
  target: number;
  realizedPnl: number;
  closeReason: string;
  bias: Bias;
  confidenceLabel: Confidence;
}

export interface FuturesPnlResponse {
  summary: FuturesPnlSummary;
  trades: FuturesPnlRow[];
}
