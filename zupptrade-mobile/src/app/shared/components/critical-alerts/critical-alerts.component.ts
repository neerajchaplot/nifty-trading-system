import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EMPTY, Subscription, interval } from 'rxjs';
import { catchError, startWith, switchMap } from 'rxjs/operators';
import { CriticalAlert } from '../../../core/models/critical-alert.model';
import { CriticalAlertService } from '../../../core/services/critical-alert.service';
import { environment } from '../../../../environments/environment';

/**
 * Monitor-screen widget listing LIVE critical alerts (agent4). Each row has an Acknowledge
 * button; on success the row is optimistically removed. Renders nothing when there are no
 * live alerts, so it takes zero space until something needs attention.
 */
@Component({
  selector: 'app-critical-alerts',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="crit-wrap" *ngIf="alerts.length > 0">
      <div class="crit-header">
        <span class="crit-heading">⚠ Critical Alerts</span>
        <span class="crit-count">{{ alerts.length }}</span>
      </div>

      <div class="crit-alert" *ngFor="let alert of alerts; trackBy: trackByAlertId">
        <div class="crit-top">
          <div class="crit-reason">{{ alert.alertReason }}</div>
          <button class="crit-ack" [disabled]="acknowledging.has(alert.alertId)" (click)="acknowledge(alert)">
            {{ acknowledging.has(alert.alertId) ? '…' : 'Acknowledge' }}
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
  `,
  styles: [`
    :host { display: block; }
    .crit-wrap { margin-bottom: 12px; }
    .crit-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; padding: 0 2px; }
    .crit-heading { font-size: 12px; font-weight: 800; color: var(--zt-red); text-transform: uppercase; letter-spacing: 0.5px; }
    .crit-count { background: var(--zt-red); color: #fff; font-size: 10px; font-weight: 700; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; }
    .crit-alert { background: rgba(220,38,38,0.06); border: 1px solid rgba(220,38,38,0.25); border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; }
    .crit-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
    .crit-reason { font-size: 12.5px; font-weight: 700; color: var(--zt-red); line-height: 1.4; }
    .crit-ack { flex-shrink: 0; background: #fff; color: var(--zt-red); border: 1px solid rgba(220,38,38,0.4); border-radius: 7px; padding: 5px 10px; font-size: 11px; font-weight: 700; font-family: inherit; }
    .crit-ack:disabled { opacity: 0.6; }
    .crit-meta { margin-top: 6px; display: flex; gap: 6px; font-size: 11px; color: var(--zt-sub); }
    .crit-details { margin-top: 8px; }
    .crit-details summary { font-size: 11px; font-weight: 600; color: var(--zt-red); cursor: pointer; }
    .crit-details pre { margin: 6px 0 0; padding: 8px 10px; background: #fff; border: 1px solid rgba(220,38,38,0.18); border-radius: 6px; font-size: 11px; color: var(--zt-sub); line-height: 1.5; max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
  `],
})
export class CriticalAlertsComponent implements OnInit, OnDestroy {
  alerts: CriticalAlert[] = [];
  readonly acknowledging = new Set<string>();
  private subs = new Subscription();

  constructor(private service: CriticalAlertService) {}

  ngOnInit(): void {
    this.subs.add(
      interval(environment.tradesPollIntervalMs).pipe(
        startWith(0),
        switchMap(() => this.service.list().pipe(catchError(() => EMPTY))),
      ).subscribe(alerts => this.merge(alerts)),
    );
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }

  acknowledge(alert: CriticalAlert): void {
    if (this.acknowledging.has(alert.alertId)) return;
    this.acknowledging.add(alert.alertId);
    this.service.acknowledge(alert.alertId).subscribe({
      next: () => {
        this.alerts = this.alerts.filter(a => a.alertId !== alert.alertId);
        this.acknowledging.delete(alert.alertId);
      },
      error: () => this.acknowledging.delete(alert.alertId),
    });
  }

  trackByAlertId(_: number, alert: CriticalAlert): string { return alert.alertId; }

  tradeRef(alert: CriticalAlert): string | null {
    const tag = alert.tradeDetails?.['tag'];
    if (typeof tag === 'string' && tag.length > 0) return tag;
    if (alert.tradeId) return 'trade ' + alert.tradeId.slice(0, 8);
    return null;
  }

  hasDetails(alert: CriticalAlert): boolean {
    return !!alert.tradeDetails && Object.keys(alert.tradeDetails).length > 0;
  }

  /** Keep rows that are mid-acknowledge from flickering back in. */
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
