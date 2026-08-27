export type AccountMode = 'SIMULATION' | 'LIVE';

export interface UserProfile {
  id: string;
  userId: string;
  displayName?: string | null;
  email?: string | null;
  accountMode?: AccountMode;
  capital: number;
  minPop: number;
  maxLossPct: number;
  maxPopPoppGap: number;
  minRocPct: number;
  spreadWidthMin: number;
  spreadWidthMax: number;
  tier1aWeight: number;
  tier1bWeight: number;
  tier2Weight: number;
  tier3Weight: number;
  tier4Weight: number;
}

export interface UpdateUserProfileRequest {
  capital?: number;
  minPop?: number;
  maxLossPct?: number;
  maxPopPoppGap?: number;
  minRocPct?: number;
  spreadWidthMin?: number;
  spreadWidthMax?: number;
  tier1aWeight?: number;
  tier1bWeight?: number;
  tier2Weight?: number;
  tier3Weight?: number;
  tier4Weight?: number;
}

export interface UserProfileAuditEntry {
  changedAt: string;
  field: string;
  oldValue: string;
  newValue: string;
}
