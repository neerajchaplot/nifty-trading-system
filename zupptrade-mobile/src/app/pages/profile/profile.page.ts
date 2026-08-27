import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  IonHeader, IonToolbar, IonTitle, IonButtons, IonBackButton, IonContent,
  IonBadge, IonRange, IonButton, IonInput, IonItem, IonLabel,
} from '@ionic/angular/standalone';
import { Router } from '@angular/router';
import { AgentUserService } from '../../core/services/agent-user.service';
import { AuthService } from '../../core/services/auth.service';
import { UserStateService } from '../../core/services/user-state.service';
import { UpdateUserProfileRequest, UserProfile, UserProfileAuditEntry } from '../../core/models/user-profile.model';
import { weightsSumTo100 } from '../../shared/util/profile.util';

type TierKey = 'tier1aWeight' | 'tier1bWeight' | 'tier2Weight' | 'tier3Weight' | 'tier4Weight';
type FormModel = Required<Omit<UpdateUserProfileRequest, never>> & Record<TierKey, number>;

const TIERS: { key: TierKey; label: string }[] = [
  { key: 'tier1aWeight', label: 'Tier 1A · Price Structure' },
  { key: 'tier1bWeight', label: 'Tier 1B · Technical' },
  { key: 'tier2Weight',  label: 'Tier 2 · Institutional Flow' },
  { key: 'tier3Weight',  label: 'Tier 3 · Volatility & Macro' },
  { key: 'tier4Weight',  label: 'Tier 4 · Commentary' },
];

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonHeader, IonToolbar, IonTitle, IonButtons, IonBackButton, IonContent,
    IonBadge, IonRange, IonButton, IonInput, IonItem, IonLabel,
  ],
  template: `
    <ion-header>
      <ion-toolbar style="--background:#ffffff; --border-color:#E2E8F0;">
        <ion-buttons slot="start">
          <ion-back-button defaultHref="/" text=""></ion-back-button>
        </ion-buttons>
        <ion-title style="color:#1B4FA8;">Profile</ion-title>
        <ion-buttons slot="end">
          <ion-badge [color]="isSim ? 'warning' : 'success'" style="margin-right:10px;">{{ modeLabel }}</ion-badge>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">

      <!-- Identity -->
      <div class="id-row" *ngIf="profile">
        <div class="id-name">{{ profile.displayName || profile.email || profile.userId }}</div>
        <div class="id-email" *ngIf="profile.email">{{ profile.email }}</div>
      </div>

      <ng-container *ngIf="profile">
        <!-- Risk settings -->
        <div class="divider-label">Risk Settings</div>
        <div class="grid2">
          <ion-item lines="none" class="fld"><ion-label position="stacked">Capital (₹)</ion-label>
            <ion-input type="number" [(ngModel)]="form.capital"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">Min PoP (%)</ion-label>
            <ion-input type="number" [(ngModel)]="form.minPop"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">Max Loss (%)</ion-label>
            <ion-input type="number" [(ngModel)]="form.maxLossPct"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">Min RoC (%)</ion-label>
            <ion-input type="number" [(ngModel)]="form.minRocPct"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">PoP–PoPP gap (%)</ion-label>
            <ion-input type="number" [(ngModel)]="form.maxPopPoppGap"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">Spread min (pts)</ion-label>
            <ion-input type="number" [(ngModel)]="form.spreadWidthMin"></ion-input></ion-item>
          <ion-item lines="none" class="fld"><ion-label position="stacked">Spread max (pts)</ion-label>
            <ion-input type="number" [(ngModel)]="form.spreadWidthMax"></ion-input></ion-item>
        </div>

        <!-- Signal weights -->
        <div style="display:flex; align-items:center; justify-content:space-between; margin:14px 4px 6px;">
          <span class="divider-label" style="margin:0;">Signal Weights</span>
          <span class="total-pill" [class.total-bad]="!weightsValid()">total {{ weightTotalPct() }}%</span>
        </div>
        <div *ngFor="let t of tiers" style="margin-bottom:6px;">
          <div style="display:flex; justify-content:space-between; font-size:12px;">
            <span style="color:var(--zt-sub);">{{ t.label }}</span>
            <span style="font-weight:700;">{{ tierWeightPct(t.key) }}%</span>
          </div>
          <ion-range [value]="tierWeightPct(t.key)" min="0" max="100" step="5"
                     (ionInput)="onTierSlider(t.key, $any($event.detail.value))"></ion-range>
        </div>

        <div *ngIf="saveError" class="error-banner" style="margin-top:8px;">{{ saveError }}</div>
        <div *ngIf="saveSuccess" style="color:var(--zt-green); font-size:12px; text-align:center; margin-top:8px;">Saved ✓</div>

        <ion-button expand="block" style="margin-top:10px;" [disabled]="!canSave" (click)="save()">
          {{ saving ? 'Saving…' : 'Save Changes' }}
        </ion-button>

        <!-- Change history -->
        <div class="divider-label" style="margin-top:18px;">Change History</div>
        <div *ngIf="auditEntries.length === 0" class="empty-sub" style="text-align:left;">No changes recorded yet.</div>
        <div *ngFor="let a of auditEntries" style="font-size:11px; color:var(--zt-sub); padding:5px 0; border-bottom:1px solid var(--zt-border);">
          <strong>{{ a.field }}</strong>: {{ a.oldValue }} → {{ a.newValue }}
          <span style="color:var(--zt-muted);"> · {{ a.changedAt | date:'dd MMM HH:mm' }}</span>
        </div>

        <ion-button expand="block" fill="clear" color="danger" style="margin-top:16px;" (click)="logout()">
          Log out
        </ion-button>
      </ng-container>
    </ion-content>
  `,
  styles: [`
    .id-row { padding:4px 4px 12px; }
    .id-name { font-size:18px; font-weight:800; color:var(--zt-text); }
    .id-email { font-size:12px; color:var(--zt-sub); }
    .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:8px; }
    .fld { --background:transparent; --padding-start:0; border:1px solid var(--zt-border); border-radius:8px; padding:2px 8px; }
    .total-pill { font-size:10px; font-weight:700; padding:2px 8px; border-radius:99px; background:rgba(22,163,74,0.12); color:var(--zt-green); }
    .total-bad { background:rgba(220,38,38,0.12); color:var(--zt-red); }
  `],
})
export class ProfilePage implements OnInit {
  private agentUser = inject(AgentUserService);
  private auth = inject(AuthService);
  private userState = inject(UserStateService);
  private router = inject(Router);

  readonly tiers = TIERS;
  profile: UserProfile | null = null;
  form: FormModel = this.emptyForm();
  auditEntries: UserProfileAuditEntry[] = [];

  saving = false;
  saveSuccess = false;
  saveError: string | null = null;

  get isSim(): boolean { return this.profile?.accountMode === 'SIMULATION'; }
  get modeLabel(): string { return this.isSim ? 'SIM' : 'LIVE'; }

  ngOnInit(): void {
    const existing = this.userState.profile;
    if (existing) {
      this.applyProfile(existing);
    } else {
      this.agentUser.me().subscribe({
        next: p => { this.userState.setProfile(p); this.applyProfile(p); },
      });
    }
  }

  private applyProfile(p: UserProfile): void {
    this.profile = p;
    this.resetForm(p);
    this.loadAudit();
  }

  private loadAudit(): void {
    if (!this.profile) return;
    this.agentUser.getAudit(this.profile.id).subscribe({
      next: entries => this.auditEntries = entries,
      error: () => {},
    });
  }

  tierWeightPct(key: TierKey): number { return Math.round((this.form[key] ?? 0) * 100); }
  onTierSlider(key: TierKey, pct: number): void { this.form[key] = +(pct / 100).toFixed(4); }
  weightTotalPct(): number { return this.tiers.reduce((sum, t) => sum + this.tierWeightPct(t.key), 0); }
  weightsValid(): boolean { return weightsSumTo100(this.tiers.map(t => this.tierWeightPct(t.key))); }
  get canSave(): boolean { return !this.saving && this.weightsValid(); }

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
      error: err => { this.saving = false; this.saveError = err?.error?.detail ?? 'Save failed.'; },
    });
  }

  logout(): void { this.auth.logout(); this.router.navigate(['/login']); }

  private resetForm(p: UserProfile): void {
    this.form = {
      capital: p.capital,
      minPop: p.minPop,
      maxLossPct: p.maxLossPct,
      maxPopPoppGap: p.maxPopPoppGap,
      minRocPct: p.minRocPct,
      spreadWidthMin: p.spreadWidthMin,
      spreadWidthMax: p.spreadWidthMax,
      tier1aWeight: p.tier1aWeight,
      tier1bWeight: p.tier1bWeight,
      tier2Weight: p.tier2Weight,
      tier3Weight: p.tier3Weight,
      tier4Weight: p.tier4Weight,
    };
  }

  private emptyForm(): FormModel {
    return {
      capital: 0, minPop: 0, maxLossPct: 0, maxPopPoppGap: 0, minRocPct: 0,
      spreadWidthMin: 0, spreadWidthMax: 0,
      tier1aWeight: 0, tier1bWeight: 0, tier2Weight: 0, tier3Weight: 0, tier4Weight: 0,
    };
  }
}
