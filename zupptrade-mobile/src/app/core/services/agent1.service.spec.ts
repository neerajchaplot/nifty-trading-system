import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Agent1Service } from './agent1.service';
import { environment } from '../../../environments/environment';

describe('Agent1Service', () => {
  let svc: Agent1Service;
  let httpMock: HttpTestingController;
  const base = environment.agent1BaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), Agent1Service],
    });
    svc = TestBed.inject(Agent1Service);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('latest GETs /latest with the expiry_date param', () => {
    svc.latest('2026-09-02').subscribe();
    const req = httpMock.expectOne(r => r.url === `${base}/latest`);
    expect(req.request.params.get('expiry_date')).toBe('2026-09-02');
    req.flush({});
  });

  it('nextExpiry GETs /next-expiry (backend-authoritative expiry)', () => {
    svc.nextExpiry().subscribe();
    const req = httpMock.expectOne(`${base}/next-expiry`);
    expect(req.request.method).toBe('GET');
    req.flush({ nextExpiry: '2026-09-02' });
  });
});
