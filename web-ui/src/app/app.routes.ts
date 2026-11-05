import { Routes } from '@angular/router';
import { signedInGuard } from './auth/signed-in.guard';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    loadComponent: () => import('./auth/google-sign-in').then((m) => m.GoogleSignIn),
  },
  { path: '', pathMatch: 'full', redirectTo: 'app/overview' },
  { path: 'app', pathMatch: 'full', redirectTo: 'app/overview' },
  {
    path: 'app/overview',
    canActivate: [signedInGuard],
    title: 'Overview',
    loadComponent: () => import('./overview/overview').then((m) => m.Overview),
  },
  {
    path: 'admin',
    canActivate: [signedInGuard],
    title: 'Administration',
    loadComponent: () => import('./admin/admin').then((m) => m.Admin),
  },
  { path: '**', redirectTo: 'app/overview' },
];
