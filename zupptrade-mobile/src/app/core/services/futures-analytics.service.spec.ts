import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FuturesAnalyticsService } from './futures-analytics.service';
import { environment } from '../../../environments/environment';

describe('FuturesAnalyticsService', () => {
  let svc: FuturesAnalyticsService;
  let httpMock: HttpTestingController;
  const base = `${environment.agent4BaseUrl}/futures`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), FuturesAnalyticsService],
    });
    svc = TestBed.inject(FuturesAnalyticsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('getFuturesPnl GETs /futures/trades', () => {
    svc.getFuturesPnl().subscribe();
    const req = httpMock.expectOne(`${base}/trades`);
    expect(req.request.method).toBe('GET');
    req.flush({ summary: {}, trades: [] });
  });

  it('passes from/to as query params when provided', () => {
    svc.getFuturesPnl('2026-01-01', '2026-01-31').subscribe();
    const req = httpMock.expectOne(r => r.url === `${base}/trades`);
    expect(req.request.params.get('from')).toBe('2026-01-01');
    expect(req.request.params.get('to')).toBe('2026-01-31');
    req.flush({ summary: {}, trades: [] });
  });
});
