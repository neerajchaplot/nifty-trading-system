import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: { accessToken: string | null; logout: jasmine.Spy };
  let router: { navigate: jasmine.Spy };

  beforeEach(() => {
    auth = { accessToken: 'jwt-123', logout: jasmine.createSpy('logout') };
    router = { navigate: jasmine.createSpy('navigate') };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('adds the Bearer token to API calls', () => {
    http.get('https://api.example.com/api/agent1/latest').subscribe();
    const req = httpMock.expectOne('https://api.example.com/api/agent1/latest');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-123');
    req.flush({});
  });

  it('does NOT add the Bearer token to /auth/ endpoints', () => {
    http.post('https://api.example.com/api/agent-user/auth/logout', {}).subscribe();
    const req = httpMock.expectOne(r => r.url.includes('/auth/'));
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('on 401 clears the session and routes to /login', () => {
    http.get('https://api.example.com/api/agent1/latest').subscribe({ error: () => {} });
    const req = httpMock.expectOne('https://api.example.com/api/agent1/latest');
    req.flush('unauthorized', { status: 401, statusText: 'Unauthorized' });
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
