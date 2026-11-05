import { DOCUMENT, inject, InjectionToken, isDevMode } from '@angular/core';
import { environment } from '../../environments/environment';

export class MissingApiConfigurationError extends Error {
  constructor() {
    super(
      'Runtime API configuration is missing or invalid. Serve this page through the web server.',
    );
  }
}

/** The web server injects deployment configuration without rendering Angular. */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => {
    const configured = inject(DOCUMENT).querySelector<HTMLMetaElement>(
      'meta[name="api-base-url"]',
    )?.content;
    let baseUrl: string;
    try {
      baseUrl = configured
        ? decodeURIComponent(configured)
        : isDevMode()
          ? environment.apiBaseUrl
          : '';
      const url = new URL(baseUrl);
      if (url.protocol !== 'http:' && url.protocol !== 'https:')
        throw new MissingApiConfigurationError();
    } catch {
      throw new MissingApiConfigurationError();
    }
    return baseUrl.trim().replace(/\/+$/, '');
  },
});
