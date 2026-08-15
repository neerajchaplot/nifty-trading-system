import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Agent1Health, Agent1Signal, ExpiryResponse, ScoreRequest } from '../models/agent1-signal.model';

@Injectable({ providedIn: 'root' })
export class Agent1Service {
  private readonly base = environment.agent1BaseUrl;

  constructor(private http: HttpClient) {}

  score(request: ScoreRequest): Observable<Agent1Signal> {
    return this.http.post<Agent1Signal>(`${this.base}/score`, request);
  }

  latest(expiryDate: string, source?: 'TRADING' | 'FUTURES'): Observable<Agent1Signal> {
    let params = new HttpParams().set('expiry_date', expiryDate);
    if (source) params = params.set('source', source);
    return this.http.get<Agent1Signal>(`${this.base}/latest`, { params });
  }

  health(): Observable<Agent1Health> {
    return this.http.get<Agent1Health>(`${this.base}/health`);
  }

  nextExpiry(): Observable<ExpiryResponse> {
    return this.http.get<ExpiryResponse>(`${this.base}/next-expiry`);
  }
}
