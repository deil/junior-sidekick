import { HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../api/api.service';
import { AuthSession } from './auth-session';

@Injectable({ providedIn: 'root' })
export class BrowserSession {
  private readonly api = inject(ApiService);
  private readonly session = inject(AuthSession);
  readonly signingOut = signal(false);
  readonly error = signal<string | null>(null);

  async restore(): Promise<void> {
    if (this.session.accessToken() || !this.session.restorationAllowed) return;

    try {
      this.session.accept(await firstValueFrom(this.api.restoreBrowserSession()));
    } catch (error: unknown) {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) throw error;
      this.session.signOut();
    }
  }

  async signOut(): Promise<void> {
    this.signingOut.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.endBrowserSession());
      this.session.signOut();
    } catch {
      this.error.set('Couldn’t sign out. Try again.');
    } finally {
      this.signingOut.set(false);
    }
  }
}
