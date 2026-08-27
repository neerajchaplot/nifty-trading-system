import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.page').then(m => m.LoginPage),
  },
  {
    path: 'profile',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/profile/profile.page').then(m => m.ProfilePage),
  },
  {
    path: '',
    loadComponent: () => import('./tabs/tabs.component').then(m => m.TabsComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'signal',
        loadComponent: () => import('./pages/signal/signal.page').then(m => m.SignalPage),
      },
      {
        path: 'trade',
        loadComponent: () => import('./pages/trade/trade.page').then(m => m.TradePage),
      },
      {
        path: 'monitor',
        loadComponent: () => import('./pages/monitor/monitor.page').then(m => m.MonitorPage),
      },
      {
        path: 'futures',
        loadComponent: () => import('./pages/futures/futures.page').then(m => m.FuturesPage),
      },
      {
        path: 'audit',
        loadComponent: () => import('./pages/audit/audit.page').then(m => m.AuditPage),
      },
      { path: '', redirectTo: 'signal', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: '' },
];
