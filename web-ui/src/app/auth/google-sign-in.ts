import { afterNextRender, Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../api/api.service';
import { AuthSession } from './auth-session';
import { authTestIds } from './auth-test-ids';
import { BrowserLogin, BrowserLoginError } from './browser-login';
import { AgentProfileStore } from '../agent-profile';
import { BrowserSession } from './browser-session';

@Component({
  selector: 'sk-google-sign-in',
  imports: [RouterLink],
  templateUrl: './google-sign-in.html',
  styleUrl: './google-sign-in.css',
})
export class GoogleSignIn {
  private readonly api = inject(ApiService);
  private readonly login = inject(BrowserLogin);
  private readonly router = inject(Router);
  protected readonly profile = inject(AgentProfileStore);
  protected readonly session = inject(AuthSession);
  protected readonly browserSession = inject(BrowserSession);
  protected readonly ids = authTestIds;
  protected readonly loading = signal(true);
  protected readonly enabled = signal(false);
  protected readonly exchanging = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    afterNextRender(() => {
      void this.prepare();
    });
  }

  protected async prepare(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const config = await firstValueFrom(this.api.getAuthConfig());
      this.enabled.set(config.enabled);
      if (!config.enabled) {
        this.error.set('Sign-in is not configured. Contact the operator of this workspace.');
        return;
      }
      const parameters = new URLSearchParams(window.location.search);
      if (parameters.has('code') || parameters.has('error'))
        history.replaceState(null, '', window.location.pathname);
      const callback = this.login.takeCallback(parameters);
      if (callback) {
        this.exchanging.set(true);
        this.session.accept(
          await firstValueFrom(this.api.exchangeLoginCode(callback.code, callback.verifier)),
        );
        await this.router.navigateByUrl('/app/overview');
      } else if (!this.session.email()) {
        await this.browserSession.restore();
        if (this.session.accessToken()) await this.router.navigateByUrl('/app/overview');
      }
    } catch (error: unknown) {
      this.error.set(
        error instanceof BrowserLoginError
          ? error.message
          : error instanceof HttpErrorResponse && error.status === 403
            ? 'This email domain is not allowed to sign in.'
            : 'Couldn’t complete sign-in. Start again with Google.',
      );
    } finally {
      this.loading.set(false);
      this.exchanging.set(false);
    }
  }

  protected async start(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      window.location.assign(await this.login.authorizationUrl());
    } catch {
      this.error.set('Couldn’t start sign-in. Allow browser storage for this site and try again.');
      this.loading.set(false);
    }
  }
}
