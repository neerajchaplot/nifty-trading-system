import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ActiveTrade } from '../models/trade.model';

@Injectable({ providedIn: 'root' })
export class Agent3Service {
  private readonly base = environment.agent3BaseUrl;

  constructor(private http: HttpClient) {}

  activeTrades(): Observable<ActiveTrade[]> {
    return this.http.get<ActiveTrade[]>(`${this.base}/active-trades`);
  }
}
