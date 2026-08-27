import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CriticalAlert } from '../models/critical-alert.model';

/** Agent 4 — critical-alert API (LIVE list + acknowledge). */
@Injectable({ providedIn: 'root' })
export class CriticalAlertService {
  private readonly base = environment.agent4BaseUrl;

  constructor(private http: HttpClient) {}

  /** LIVE (unacknowledged) alerts, newest first. */
  list(): Observable<CriticalAlert[]> {
    return this.http.get<CriticalAlert[]>(`${this.base}/critical-alerts`);
  }

  /** Acknowledge one alert (LIVE → ACKNOWLEDGED). */
  acknowledge(alertId: string): Observable<unknown> {
    return this.http.post(`${this.base}/critical-alerts/${alertId}/acknowledge`, {});
  }
}
