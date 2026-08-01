import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CriticalAlert } from '../models/critical-alert.model';

/**
 * Client for agent4's critical-alert API. Base path matches {@link AuditService} (the other
 * agent4 client) — the dev proxy maps /api/agent4 → agent4 :8084 /api/v1/agent4.
 */
@Injectable({ providedIn: 'root' })
export class CriticalAlertService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/agent4';

  /** LIVE (unacknowledged) alerts, newest first. */
  list(): Observable<CriticalAlert[]> {
    return this.http.get<CriticalAlert[]>(`${this.base}/critical-alerts`);
  }

  /** Acknowledge one alert (LIVE → ACKNOWLEDGED). */
  acknowledge(alertId: string): Observable<unknown> {
    return this.http.post(`${this.base}/critical-alerts/${alertId}/acknowledge`, {});
  }
}
