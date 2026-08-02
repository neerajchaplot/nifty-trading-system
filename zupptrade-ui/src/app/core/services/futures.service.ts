import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  FuturesConfirmRequest,
  FuturesPlanCard,
  FuturesRecommendRequest,
} from '../models/futures.model';

/** Agent 2 — Nifty futures intraday trade plan endpoints. */
@Injectable({ providedIn: 'root' })
export class FuturesService {
  private readonly base = `${environment.agent2BaseUrl}/futures`;

  constructor(private http: HttpClient) {}

  recommend(request: FuturesRecommendRequest): Observable<FuturesPlanCard> {
    return this.http.post<FuturesPlanCard>(`${this.base}/recommend`, request);
  }

  confirm(request: FuturesConfirmRequest): Observable<FuturesPlanCard> {
    return this.http.post<FuturesPlanCard>(`${this.base}/confirm`, request);
  }

  /** Screen 2 — accepted plans for today (dormant + active). */
  listActive(): Observable<FuturesPlanCard[]> {
    return this.http.get<FuturesPlanCard[]>(`${this.base}/plans/active`);
  }
}
