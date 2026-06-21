import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../../environments/environment';

export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const withKey = req.clone({
    headers: req.headers.set('X-API-Key', environment.apiKey),
  });
  return next(withKey);
};
