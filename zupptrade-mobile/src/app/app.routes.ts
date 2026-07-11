import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./tabs/tabs.component').then(m => m.TabsComponent),
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
        path: 'audit',
        loadComponent: () => import('./pages/audit/audit.page').then(m => m.AuditPage),
      },
      { path: '', redirectTo: 'signal', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: '' },
];
