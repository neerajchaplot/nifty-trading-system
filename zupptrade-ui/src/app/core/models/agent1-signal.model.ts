import { Bias, Confidence, Strength, VixRegime } from './enums';

export interface Agent1Signal {
  id: string;
  timestamp: string;
  expiryDate: string;
  bias: Bias;
  strength: Strength;
  compositeScore: number;
  confidenceScore: number;
  confidence: Confidence;
  spot: number | null;         // Nifty 50 level at scoring time (last close when market shut)
  vixLevel: number | null;
  vixRegime: VixRegime | null;
  vixDirection: string | null;
  scoreBreakdown: string | null;   // serialised JSON — parse on demand
  commentaryDivergence: boolean | null;
  keyLevels: string | null;
}

export interface Agent1Health {
  status: string;
  lastRun: string | null;
  dataFreshness: string | null;
}

export interface ScoreRequest {
  commentary?: string;
  marketaux_fetch: boolean;
}
