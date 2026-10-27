import { inject, isDevMode, TransferState } from '@angular/core';
import { environment } from '../../environments/environment';
import { API_BASE_URL_STATE } from './api-base-url';
import { IS_DISCOVERING_ROUTES } from '@angular/ssr';

export function readServerApiBaseUrl(allowDevelopmentDefault = false): string {
  const value =
    process.env['API_BASE_URL'] ?? (allowDevelopmentDefault ? environment.apiBaseUrl : undefined);
  if (!value?.trim()) {
    throw new Error('API_BASE_URL is required. Set it before starting the SSR server.');
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('API_BASE_URL must be an absolute HTTP or HTTPS URL.');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('API_BASE_URL must be an absolute HTTP or HTTPS URL.');
  }

  return value.trim().replace(/\/+$/, '');
}

export function serverApiBaseUrl(): string {
  // Angular constructs services while extracting routes, before deployment config exists.
  if (inject(IS_DISCOVERING_ROUTES, { optional: true })) {
    return '';
  }
  const baseUrl = readServerApiBaseUrl(isDevMode());
  inject(TransferState).set(API_BASE_URL_STATE, baseUrl);
  return baseUrl;
}
