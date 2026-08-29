import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ExecuteTradeRequest,
  ExecuteTradeResponse,
  MarginCheckRequest,
  MarginCheckResult,
  MarginUtilization,
} from '../models/trade.model';

/**
 * Agent 5 — execution. Mirrors zupptrade-ui's Agent5Service exactly:
 *   execute            → POST /execute
 *   checkMargin        → POST /margin/check
 *   marginUtilization  → GET  /margin/utilization
 */
@Injectable({ providedIn: 'root' })
export class Agent5Service {
  private readonly base = environment.agent5BaseUrl;

  constructor(private http: HttpClient) {}

  execute(request: ExecuteTradeRequest): Observable<ExecuteTradeResponse> {
    return this.http.post<ExecuteTradeResponse>(`${this.base}/execute`, request);
  }

  checkMargin(request: MarginCheckRequest): Observable<MarginCheckResult> {
    return this.http.post<MarginCheckResult>(`${this.base}/margin/check`, request);
  }

  marginUtilization(): Observable<MarginUtilization> {
    return this.http.get<MarginUtilization>(`${this.base}/margin/utilization`);
  }
}
