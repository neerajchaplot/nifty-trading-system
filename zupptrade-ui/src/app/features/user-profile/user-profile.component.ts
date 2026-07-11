import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AgentUserService } from '../../core/services/agent-user.service';
import { UserStateService } from '../../core/services/user-state.service';
import { UpdateUserProfileRequest, UserProfile, UserProfileAuditEntry } from '../../core/models/user-profile.model';

interface WeightTier {
  key: keyof Pick<UpdateUserProfileRequest, 'tier1aWeight' | 'tier1bWeight' | 'tier2Weight' | 'tier3Weight' | 'tier4Weight'>;
  label: string;
  description: string;
}

const WEIGHT_TIERS: WeightTier[] = [
  { key: 'tier1aWeight', label: 'Tier 1A — Price Structure',       description: 'EMA, PCR, futures premium, max pain (default 30%)' },
  { key: 'tier1bWeight', label: 'Tier 1B — Technical Indicators',  description: 'RSI, MACD, EMA crossovers, candlestick patterns (default 20%)' },
  { key: 'tier2Weight',  label: 'Tier 2 — Institutional Flow',     description: 'FII/DII net futures, options, cash flows (default 30%)' },
  { key: 'tier3Weight',  label: 'Tier 3 — Volatility & Macro',     description: 'VIX change, OI shift, Gift Nifty premium (default 10%)' },
  { key: 'tier4Weight',  label: 'Tier 4 — Commentary & Sentiment', description: 'Marketaux news sentiment, LLM commentary extraction (default 10%)' },
];

@Component({
  selector: 'app-user-profile',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user-profile.component.html',
  styleUrls: ['./user-profile.component.scss'],
})
export class UserProfileComponent implements OnInit {
  profile: UserProfile | null = null;
  form: UpdateUserProfileRequest = this.emptyForm();
  auditEntries: UserProfileAuditEntry[] = [];
  readonly tiers = WEIGHT_TIERS;

  saving = false;
  saveSuccess = false;
  saveError: string | null = null;
  loadingAudit = false;

  constructor(
    private agentUser: AgentUserService,
    private userState: UserStateService,
  ) {}

  ngOnInit(): void {
    const existing = this.userState.profile;
    if (existing) {
      this.profile = existing;
      this.resetForm(existing);
    } else {
      this.agentUser.me().subscribe({
        next: p => {
          this.profile = p;
          this.userState.setProfile(p);
          this.resetForm(p);
        },
      });
    }
    this.loadAudit();
  }

  get weightTotal(): number {
    return +(
      this.form.tier1aWeight +
      this.form.tier1bWeight +
      this.form.tier2Weight +
      this.form.tier3Weight +
      this.form.tier4Weight
    ).toFixed(4);
  }

  get weightValid(): boolean {
    return Math.abs(this.weightTotal - 1) < 0.0001;
  }

  get canSave(): boolean {
    return !this.saving && this.weightValid;
  }

  tierWeightPct(key: WeightTier['key']): number {
    return Math.round((this.form[key] as number) * 100);
  }

  onTierSliderChange(key: WeightTier['key'], pct: number): void {
    this.form[key] = +(pct / 100).toFixed(4);
  }

  save(): void {
    if (!this.profile || !this.canSave) return;
    this.saving = true;
    this.saveSuccess = false;
    this.saveError = null;

    this.agentUser.updateProfile(this.profile.id, this.form).subscribe({
      next: updated => {
        this.profile = updated;
        this.userState.setProfile(updated);
        this.resetForm(updated);
        this.saving = false;
        this.saveSuccess = true;
        this.loadAudit();
        setTimeout(() => (this.saveSuccess = false), 3000);
      },
      error: err => {
        this.saving = false;
        this.saveError = err?.error?.detail ?? err?.message ?? 'Save failed';
      },
    });
  }

  resetForm(p: UserProfile): void {
    this.form = {
      capital:       p.capital,
      minPop:        p.minPop,
      maxLossPct:    p.maxLossPct,
      maxPopPoppGap: p.maxPopPoppGap,
      minRocPct:     p.minRocPct,
      spreadWidthMin: p.spreadWidthMin,
      spreadWidthMax: p.spreadWidthMax,
      tier1aWeight:  p.tier1aWeight,
      tier1bWeight:  p.tier1bWeight,
      tier2Weight:   p.tier2Weight,
      tier3Weight:   p.tier3Weight,
      tier4Weight:   p.tier4Weight,
    };
  }

  auditLabel(val: unknown): string {
    if (val == null) return '—';
    if (typeof val === 'number') return val.toString();
    return String(val);
  }

  auditFieldLabel(key: string): string {
    const labels: Record<string, string> = {
      capital: 'Capital (₹)',
      minPop: 'Min PoP (%)',
      maxLossPct: 'Max Loss (%)',
      maxPopPoppGap: 'Max PoP Gap (%)',
      minRocPct: 'Min RoC (%)',
      spreadWidthMin: 'Spread Min',
      spreadWidthMax: 'Spread Max',
      tier1aWeight: 'Tier 1A Weight',
      tier1bWeight: 'Tier 1B Weight',
      tier2Weight:  'Tier 2 Weight',
      tier3Weight:  'Tier 3 Weight',
      tier4Weight:  'Tier 4 Weight',
    };
    return labels[key] ?? key;
  }

  changedFields(entry: UserProfileAuditEntry): string[] {
    return Object.keys(entry.newValues).filter(
      k => String(entry.oldValues[k]) !== String(entry.newValues[k])
    );
  }

  private emptyForm(): UpdateUserProfileRequest {
    return {
      capital: 500000,
      minPop: 0.80,
      maxLossPct: 1.50,
      maxPopPoppGap: 15.00,
      minRocPct: 0.50,
      spreadWidthMin: 50,
      spreadWidthMax: 150,
      tier1aWeight: 0.3000,
      tier1bWeight: 0.2000,
      tier2Weight:  0.3000,
      tier3Weight:  0.1000,
      tier4Weight:  0.1000,
    };
  }

  private loadAudit(): void {
    if (!this.profile) return;
    this.loadingAudit = true;
    this.agentUser.getAudit(this.profile.id).subscribe({
      next: entries => {
        this.auditEntries = entries;
        this.loadingAudit = false;
      },
      error: () => { this.loadingAudit = false; },
    });
  }
}
