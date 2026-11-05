import { inject, Injectable } from '@angular/core';
import * as z from 'zod/mini';
import { ApiService } from '../api/api.service';

const STORAGE_KEY = 'sidekick.pending-login';
const PendingLoginSchema = z.strictObject({
  state: z.string(),
  verifier: z.string(),
  created_at: z.number(),
});

export class BrowserLoginError extends Error {}

function randomValue(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

/** Only the short-lived login verifier is stored across the Google redirect, never a bearer token. */
@Injectable({ providedIn: 'root' })
export class BrowserLogin {
  private readonly api = inject(ApiService);

  async authorizationUrl(): Promise<string> {
    const verifier = randomValue();
    const state = randomValue();
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ verifier, state, created_at: Date.now() }),
    );
    return this.api.googleAuthorizationUrl(base64Url(new Uint8Array(digest)), state);
  }

  takeCallback(parameters: URLSearchParams): { code: string; verifier: string } | null {
    if (!parameters.has('code') && !parameters.has('error')) return null;
    const saved = sessionStorage.getItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
    if (parameters.has('error')) {
      throw new BrowserLoginError(
        parameters.get('error') === 'domain'
          ? 'This email domain is not allowed to sign in.'
          : 'Google sign-in failed. Try again.',
      );
    }
    const parsed = PendingLoginSchema.safeParse(saved ? JSON.parse(saved) : null);
    const code = parameters.get('code');
    if (
      !parsed.success ||
      !code ||
      parameters.get('state') !== parsed.data.state ||
      Date.now() - parsed.data.created_at > 600000 ||
      parsed.data.created_at > Date.now()
    ) {
      throw new BrowserLoginError('This sign-in request is invalid or expired. Start again.');
    }
    return { code, verifier: parsed.data.verifier };
  }
}
