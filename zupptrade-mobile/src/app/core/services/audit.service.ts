import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PortfolioSummary, SignalQuality, TradeListResponse } from '../models/audit.models';

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/agent4';

  getSummary(from?: string, to?: string): Observable<PortfolioSummary> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to)   params = params.set('to', to);
    return this.http.get<PortfolioSummary>(`${this.base}/summary`, { params });
  }

  getTrades(page: number, size: number, from?: string, to?: string): Observable<TradeListResponse> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (from) params = params.set('from', from);
    if (to)   params = params.set('to', to);
    return this.http.get<TradeListResponse>(`${this.base}/trades`, { params });
  }

  getSignalQuality(from?: string, to?: string): Observable<SignalQuality> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to)   params = params.set('to', to);
    return this.http.get<SignalQuality>(`${this.base}/signal-quality`, { params });
  }
}
