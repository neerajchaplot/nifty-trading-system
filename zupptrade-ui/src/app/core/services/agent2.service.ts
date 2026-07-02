import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CalculateOverrideRequest,
  CalculateOverrideResult,
  ConfirmRequest,
  RecommendRequest,
  TradeCard,
} from '../models/trade.model';

@Injectable({ providedIn: 'root' })
export class Agent2Service {
  private readonly base = environment.agent2BaseUrl;

  constructor(private http: HttpClient) {}

  recommend(request: RecommendRequest): Observable<TradeCard> {
    return this.http.post<TradeCard>(`${this.base}/recommend`, request);
  }

  confirm(request: ConfirmRequest): Observable<TradeCard> {
    return this.http.post<TradeCard>(`${this.base}/confirm`, request);
  }

  calculateOverride(request: CalculateOverrideRequest): Observable<CalculateOverrideResult> {
    return this.http.post<CalculateOverrideResult>(`${this.base}/calculate-override`, request);
  }
}
