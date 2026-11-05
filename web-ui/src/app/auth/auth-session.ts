import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { AccessToken, UserProfile } from '../api/api.schemas';

/** Access tokens stay in memory; the backend cookie restores them after reload. */
@Injectable({ providedIn: 'root' })
export class AuthSession {
  private readonly credentials = signal<AccessToken | null>(null);
  private readonly profile = signal<UserProfile | null>(null);
  private expiryTimer: ReturnType<typeof setTimeout> | undefined;
  readonly email = computed(() => this.profile()?.email ?? null);
  restorationAllowed = true;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
  }

  accept(credentials: AccessToken): void {
    const remaining = Date.parse(credentials.expires_at) - Date.now();
    if (remaining <= 0) throw new Error('The access token has expired. Sign in again.');
    clearTimeout(this.expiryTimer);
    this.credentials.set(credentials);
    this.restorationAllowed = true;
    this.profile.set(null);
    this.expiryTimer = setTimeout(() => this.clear(), remaining);
  }

  identify(profile: UserProfile): void {
    this.profile.set(profile);
  }

  accessToken(): string | null {
    const credentials = this.credentials();
    return credentials && Date.parse(credentials.expires_at) > Date.now()
      ? credentials.access_token
      : null;
  }

  signOut(): void {
    this.restorationAllowed = false;
    this.clear();
  }

  private clear(): void {
    clearTimeout(this.expiryTimer);
    this.credentials.set(null);
    this.profile.set(null);
  }
}
