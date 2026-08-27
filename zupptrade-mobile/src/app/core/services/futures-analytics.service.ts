import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { FuturesPnlResponse } from '../models/futures-analytics.model';

/** Agent 4 — futures P&L filter (CLOSED futures trades). */
@Injectable({ providedIn: 'root' })
export class FuturesAnalyticsService {
  private readonly base = `${environment.agent4BaseUrl}/futures`;

  constructor(private http: HttpClient) {}

  getFuturesPnl(from?: string, to?: string): Observable<FuturesPnlResponse> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<FuturesPnlResponse>(`${this.base}/trades`, { params });
  }
}
