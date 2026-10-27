import { inject, InjectionToken, makeStateKey, TransferState } from '@angular/core';

export const API_BASE_URL_STATE = makeStateKey<string>('apiBaseUrl');

export class MissingApiConfigurationError extends Error {
  constructor() {
    super('Runtime API configuration is missing. Serve this page through the SSR server.');
  }
}

/** SSR transfers the deployment URL so browser requests use the same backend. */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => {
    const baseUrl = inject(TransferState).get(API_BASE_URL_STATE, '');
    if (!baseUrl) {
      throw new MissingApiConfigurationError();
    }
    return baseUrl;
  },
});
