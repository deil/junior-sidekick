import { inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, from, map, of, switchMap, throwError } from 'rxjs';
import { AuthSession } from './auth-session';
import { ApiService } from '../api/api.service';
import { BrowserSession } from './browser-session';

/** The API establishes identity. An access token is opaque to the frontend. */
export const signedInGuard: CanActivateFn = () => {
  const session = inject(AuthSession);
  const router = inject(Router);
  const api = inject(ApiService);
  return from(inject(BrowserSession).restore()).pipe(
    switchMap(() => api.getCurrentUser()),
    map((profile) => {
      session.identify(profile);
      return true;
    }),
    catchError((error: unknown) =>
      error instanceof HttpErrorResponse && error.status === 401
        ? of(router.createUrlTree(['/login']))
        : throwError(() => error),
    ),
  );
};
