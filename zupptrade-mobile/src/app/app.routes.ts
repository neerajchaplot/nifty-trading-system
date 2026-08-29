import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.page').then(m => m.LoginPage),
  },
  {
    path: '',
    loadComponent: () => import('./shell/shell.component').then(m => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'trading',
        loadComponent: () => import('./features/trading/trading.page').then(m => m.TradingPage),
      },
      {
        path: 'futures',
        loadComponent: () => import('./pages/futures/futures.page').then(m => m.FuturesPage),
      },
      {
        path: 'audit',
        loadComponent: () => import('./pages/audit/audit.page').then(m => m.AuditPage),
      },
      {
        path: 'profile',
        loadComponent: () => import('./pages/profile/profile.page').then(m => m.ProfilePage),
      },
      { path: '', redirectTo: 'trading', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: '' },
];
