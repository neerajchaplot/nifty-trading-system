/**
 * A user-actionable critical alert served by agent4 (/api/agent4/critical-alerts).
 * Mirrors CriticalAlertDto on the backend. `tradeDetails` is the transparent JSON snapshot
 * of trade state the system captured when it raised the alert.
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
