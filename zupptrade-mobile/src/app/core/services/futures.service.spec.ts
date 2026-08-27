import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FuturesService } from './futures.service';
import { environment } from '../../../environments/environment';

describe('FuturesService', () => {
  let svc: FuturesService;
  let httpMock: HttpTestingController;
  const base = `${environment.agent2BaseUrl}/futures`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), FuturesService],
    });
    svc = TestBed.inject(FuturesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('recommend POSTs the request to /futures/recommend', () => {
    svc.recommend({ userProfileId: 'p1', runPhase: 900 }).subscribe();
    const req = httpMock.expectOne(`${base}/recommend`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ userProfileId: 'p1', runPhase: 900 });
    req.flush({});
  });

  it('confirm POSTs the selected arm to /futures/confirm', () => {
    svc.confirm({ planId: 'plan1', action: 'CONFIRM', selectedArm: 'LONG_BREAKOUT' }).subscribe();
    const req = httpMock.expectOne(`${base}/confirm`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.selectedArm).toBe('LONG_BREAKOUT');
    req.flush({});
  });

  it('listActive GETs /futures/plans/active', () => {
    svc.listActive().subscribe();
    const req = httpMock.expectOne(`${base}/plans/active`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
