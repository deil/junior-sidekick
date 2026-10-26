import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'app/overview' },
  { path: 'app', pathMatch: 'full', redirectTo: 'app/overview' },
  {
    path: 'app/overview',
    title: 'Overview',
    loadComponent: () => import('./overview/overview').then((m) => m.Overview),
  },
  {
    path: 'admin',
    title: 'Administration',
    loadComponent: () => import('./admin/admin').then((m) => m.Admin),
  },
  { path: '**', redirectTo: 'app/overview' },
];
