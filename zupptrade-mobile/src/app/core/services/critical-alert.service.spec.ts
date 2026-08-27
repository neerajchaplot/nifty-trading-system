import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CriticalAlertService } from './critical-alert.service';
import { environment } from '../../../environments/environment';

describe('CriticalAlertService', () => {
  let svc: CriticalAlertService;
  let httpMock: HttpTestingController;
  const base = environment.agent4BaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), CriticalAlertService],
    });
    svc = TestBed.inject(CriticalAlertService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list GETs /critical-alerts', () => {
    svc.list().subscribe();
    const req = httpMock.expectOne(`${base}/critical-alerts`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('acknowledge POSTs to /critical-alerts/{id}/acknowledge', () => {
    svc.acknowledge('a-1').subscribe();
    const req = httpMock.expectOne(`${base}/critical-alerts/a-1/acknowledge`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});
