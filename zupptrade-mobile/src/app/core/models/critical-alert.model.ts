/**
 * A user-actionable critical alert served by agent4 (/api/agent4/critical-alerts).
 * `tradeDetails` is the JSON snapshot of trade state captured when the alert was raised.
 */
export interface CriticalAlert {
  alertId: string;
  tradeId: string | null;
  alertReason: string;
  tradeDetails: Record<string, unknown>;
  status: string;
  createdAt: string;
  acknowledgedAt: string | null;
}
