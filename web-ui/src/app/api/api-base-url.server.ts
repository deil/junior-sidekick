import { inject, TransferState } from '@angular/core';
import { environment } from '../../environments/environment';
import { API_BASE_URL_STATE } from './api-base-url';

export function serverApiBaseUrl(): string {
  const value = process.env['API_BASE_URL'] ?? environment.apiBaseUrl;
  const url = new URL(value);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('API_BASE_URL must be an absolute HTTP or HTTPS URL.');
  }

  const baseUrl = value.replace(/\/+$/, '');
  inject(TransferState).set(API_BASE_URL_STATE, baseUrl);
  return baseUrl;
}
