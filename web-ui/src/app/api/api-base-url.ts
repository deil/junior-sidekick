import { inject, InjectionToken, makeStateKey, TransferState } from '@angular/core';
import { environment } from '../../environments/environment';

export const API_BASE_URL_STATE = makeStateKey<string>('apiBaseUrl');

/** SSR transfers the deployment URL so browser requests use the same backend. */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => inject(TransferState).get(API_BASE_URL_STATE, environment.apiBaseUrl),
});
