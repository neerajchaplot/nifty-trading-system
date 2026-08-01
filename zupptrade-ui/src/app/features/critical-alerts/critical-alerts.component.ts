import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EMPTY, Subscription, interval } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { CriticalAlert } from '../../core/models/critical-alert.model';
import { CriticalAlertService } from '../../core/services/critical-alert.service';
import { environment } from '../../../environments/environment';

/**
 * Trading-screen widget listing LIVE critical alerts (agent4). Each row carries an
 * Acknowledge button; on success the row is removed and will not return on the next poll
 * (backend flips it to ACKNOWLEDGED). Renders nothing when there are no live alerts, so it
 * takes zero space above the live monitor until something actually needs attention.
 *
 * Self-contained polling mirrors the live-monitor's own data fetching — it reuses the
 * shared trades poll interval so alerts refresh on the same cadence as the monitor cards.
 */
@Component({
  selector: 'app-critical-alerts',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="crit-wrap" *ngIf="alerts.length > 0">
      <div class="section-header">
        <span class="section-heading crit-heading">⚠ Critical Alerts</span>
        <div class="header-meta">
          <span class="slot-label">action required</span>
          <span class="crit-count-badge">{{ alerts.length }}</span>
        </div>
      </div>

      <div class="crit-grid">
        <div class="crit-alert" *ngFor="let alert of alerts; trackBy: trackByAlertId">
          <div class="crit-top">
            <div class="crit-reason">{{ alert.alertReason }}</div>
            <button class="crit-ack-btn"
                    [disabled]="acknowledging.has(alert.alertId)"
                    (click)="acknowledge(alert)">
              {{ acknowledging.has(alert.alertId) ? 'Acknowledging…' : 'Acknowledge' }}
            </button>
          </div>

          <div class="crit-meta">
            <span>{{ timeAgo(alert.createdAt) }}</span>
            <span *ngIf="tradeRef(alert) as ref">· {{ ref }}</span>
          </div>

          <details class="crit-details" *ngIf="hasDetails(alert)">
            <summary>Trade snapshot</summary>
            <pre>{{ alert.tradeDetails | json }}</pre>
          </details>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .crit-wrap { padding: 16px 20px 0; display: flex; flex-direction: column; }

    /* Section header — matches live-monitor */
    .section-header {
      display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;
    }
    .section-heading {
      font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.6px;
    }
    .crit-heading { color: #B91C1C; }
    .header-meta { display: flex; align-items: center; gap: 8px; }
    .slot-label { font-size: 11px; color: #DC2626; font-weight: 600; letter-spacing: 0.3px; }
    .crit-count-badge {
      background: #DC2626; color: #fff; font-size: 10px; font-weight: 700;
      min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
      display: flex; align-items: center; justify-content: center;
    }

    .crit-grid { display: flex; flex-direction: column; gap: 10px; }

    /* Alert card — mirrors .live-trade.warn-t3 palette */
    .crit-alert {
      background: #FEF2F2;
      border: 1.5px solid #FECACA;
      border-radius: 10px;
      padding: 10px 14px;
      box-shadow: 0 1px 3px rgba(0,0,0,.07);
    }
    .crit-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
    .crit-reason {
      font-size: 12.5px; font-weight: 700; color: #7F1D1D; line-height: 1.4;
    }
    .crit-ack-btn {
      flex-shrink: 0;
      background: #fff; color: #B91C1C;
      border: 1.5px solid #FCA5A5; border-radius: 7px;
      padding: 5px 12px; font-size: 11px; font-weight: 700; cursor: pointer;
      transition: background .15s, color .15s, border-color .15s;
    }
    .crit-ack-btn:hover:not(:disabled) { background: #B91C1C; color: #fff; border-color: #B91C1C; }
    .crit-ack-btn:disabled { opacity: .6; cursor: default; }

    .crit-meta {
      margin-top: 6px; display: flex; gap: 6px;
      font-size: 11px; color: #B45454;
    }

    .crit-details { margin-top: 8px; }
    .crit-details summary {
      font-size: 11px; font-weight: 600; color: #B91C1C; cursor: pointer; user-select: none;
    }
    .crit-details pre {
      margin: 6px 0 2px; padding: 8px 10px;
      background: #fff; border: 1px solid #FEE2E2; border-radius: 6px;
      font-size: 11px; color: #475569; line-height: 1.5;
      max-height: 180px; overflow: auto;
      white-space: pre-wrap; word-break: break-word;
    }
  `],
})
export class CriticalAlertsComponent implements OnInit, OnDestroy {
  alerts: CriticalAlert[] = [];
  readonly acknowledging = new Set<string>();

  private subs = new Subscription();

  constructor(private service: CriticalAlertService) {}

  ngOnInit(): void {
    this.fetch();
    const sub = interval(environment.tradesPollIntervalMs)
      .pipe(switchMap(() => this.service.list().pipe(catchError(() => EMPTY))))
      .subscribe(alerts => this.merge(alerts));
    this.subs.add(sub);
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  acknowledge(alert: CriticalAlert): void {
    if (this.acknowledging.has(alert.alertId)) return;
    this.acknowledging.add(alert.alertId);
    this.service.acknowledge(alert.alertId).subscribe({
      next: () => {
        // Optimistically drop the row; the next poll confirms it stays gone.
        this.alerts = this.alerts.filter(a => a.alertId !== alert.alertId);
        this.acknowledging.delete(alert.alertId);
      },
      error: () => {
        // Re-enable so the user can retry; a 404 (already acknowledged) is cleared on next poll.
        this.acknowledging.delete(alert.alertId);
      },
    });
  }

  trackByAlertId(_: number, alert: CriticalAlert): string {
    return alert.alertId;
  }

  tradeRef(alert: CriticalAlert): string | null {
    const tag = alert.tradeDetails?.['tag'];
    if (typeof tag === 'string' && tag.length > 0) return tag;
    if (alert.tradeId) return 'trade ' + alert.tradeId.slice(0, 8);
    return null;
  }

  hasDetails(alert: CriticalAlert): boolean {
    return !!alert.tradeDetails && Object.keys(alert.tradeDetails).length > 0;
  }

  private fetch(): void {
    this.service.list().pipe(catchError(() => EMPTY)).subscribe(alerts => this.merge(alerts));
  }

  /** Replace the list but keep rows that are mid-acknowledge from flickering back in. */
  private merge(alerts: CriticalAlert[]): void {
    this.alerts = alerts.filter(a => !this.acknowledging.has(a.alertId));
  }

  timeAgo(iso: string): string {
    const then = new Date(iso).getTime();
    if (isNaN(then)) return '';
    const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
    if (secs < 60) return secs + 's ago';
    const mins = Math.floor(secs / 60);
    if (mins < 60) return mins + 'm ago';
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return hrs + 'h ago';
    return Math.floor(hrs / 24) + 'd ago';
  }
}
