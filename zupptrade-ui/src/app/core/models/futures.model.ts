import { Bias, Confidence } from './enums';
import {
  ArmCardStatus,
  ArmReachability,
  ConfirmAction,
  FutureArmType,
  FuturePlanStatus,
  OpenZone,
  TradeDirection,
} from './enums';

/** Request to build a futures trade plan (Agent 2 /futures/recommend). */
export interface FuturesRecommendRequest {
  agent1SignalId?: string;   // optional — backend regenerates the signal from admin commentary
  userProfileId: string;
  runPhase?: number;
}

/** User's decision on a primed plan (Agent 2 /futures/confirm). */
export interface FuturesConfirmRequest {
  planId: string;
  action: ConfirmAction;
  selectedArm?: FutureArmType;
  overrideLots?: number | null;
}

export interface FuturesCamarilla {
  range: number;
  pivot: number;
  h3: number;
  h4: number;
  l3: number;
  l4: number;
}

export interface FuturesPriorOhlc {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
}

/** One selectable trade on the card. */
export interface FuturesArmCard {
  armType: FutureArmType;
  label: string;            // plain UI label, e.g. "Buy the dip"
  direction: TradeDirection;
  status: ArmCardStatus;
  blockedReason?: string | null;
  entry: number;
  stop: number;
  target: number;
  riskPoints: number;
  rewardPoints: number;
  rrGross: number;
  rrAfterCost: number;
  costPoints: number;
  probabilityPct: number;
  lots: number;
  lotSize: number;
  riskPerLot: number;
  riskTotal: number;
  marginEstimate: number;
  notional: number;
  // Live, on-read overlay: is this arm's entry still catchable at the current level? (null = unknown)
  reachability?: ArmReachability | null;
}

/** Full futures trade card — all four arms plus every calculation. */
export interface FuturesPlanCard {
  planId: string;
  planCode: string;
  status: FuturePlanStatus;
  tradeDate: string;
  runPhase: number;
  instrumentKey?: string | null;
  bias: Bias;
  confidenceScore: number;
  confidenceLabel: Confidence;
  openZone: OpenZone;
  keyLevels: FuturesCamarilla;
  priorOhlc: FuturesPriorOhlc;
  openPx: number;
  currentLevel?: number | null;   // live Nifty level the arms' reachability was judged against
  confidenceGatePassed: boolean;
  minConfidence: number;
  compressionRci: number;
  compressionThreshold: number;
  compressed: boolean;
  roundTripCostPoints: number;
  primaryArm?: FutureArmType | null;
  noTradeReason?: string | null;
  arms: FuturesArmCard[];
  createdAt: string;
}
