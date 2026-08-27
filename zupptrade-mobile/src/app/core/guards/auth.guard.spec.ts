import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('authGuard', () => {
  let router: { navigate: jasmine.Spy };

  function run(authenticated: boolean): boolean {
    router = { navigate: jasmine.createSpy('navigate') };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { isAuthenticated: () => authenticated } },
        { provide: Router, useValue: router },
      ],
    });
    return TestBed.runInInjectionContext(
      () => authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean;
  }

  it('allows an authenticated user', () => {
    expect(run(true)).toBeTrue();
  });

  it('blocks an anonymous user and redirects to /login', () => {
    expect(run(false)).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
