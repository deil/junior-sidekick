import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, throwError } from 'rxjs';
import { API_BASE_URL } from './api-base-url';
import { AuthSession } from '../auth/auth-session';
import {
  AgentProfile,
  AgentProfileSchema,
  ReportingPeriod,
  UsageStats,
  UsageStatsSchema,
  AccessToken,
  AccessTokenSchema,
  AuthConfig,
  AuthConfigSchema,
  UserProfile,
  UserProfileSchema,
  BrowserSignOutSchema,
  DeletedMessage,
  DeletedMessageSchema,
} from './api.schemas';

/** The only HTTP boundary. Every response is unknown until its Zod schema accepts it. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  private readonly session = inject(AuthSession);

  getAuthConfig(): Observable<AuthConfig> {
    return this.http
      .get<unknown>(`${this.baseUrl}/api/web/auth/config`, { transferCache: false })
      .pipe(map((response) => AuthConfigSchema.parse(response)));
  }

  googleAuthorizationUrl(challenge: string, state: string): string {
    const url = new URL(`${this.baseUrl}/oauth2/authorization/google`);
    url.searchParams.set('sidekick_code_challenge', challenge);
    url.searchParams.set('sidekick_state', state);
    return url.toString();
  }

  exchangeLoginCode(code: string, verifier: string): Observable<AccessToken> {
    return this.http
      .post<unknown>(
        `${this.baseUrl}/api/web/auth/token`,
        { code, code_verifier: verifier },
        { transferCache: false, withCredentials: true },
      )
      .pipe(map((response) => AccessTokenSchema.parse(response)));
  }

  getAgentProfile(): Observable<AgentProfile> {
    return this.http
      .get<unknown>(`${this.baseUrl}/api/web/sidekick/profile`)
      .pipe(map((response) => AgentProfileSchema.parse(response)));
  }

  restoreBrowserSession(): Observable<AccessToken> {
    return this.http
      .get<unknown>(`${this.baseUrl}/api/web/auth/session`, {
        withCredentials: true,
        transferCache: false,
        headers: { 'X-Sidekick-Session': '1' },
      })
      .pipe(map((response) => AccessTokenSchema.parse(response)));
  }

  endBrowserSession(): Observable<void> {
    return this.http
      .delete<unknown>(`${this.baseUrl}/api/web/auth/session`, {
        withCredentials: true,
        transferCache: false,
        headers: { 'X-Sidekick-Session': '1' },
      })
      .pipe(
        map((response) => {
          BrowserSignOutSchema.parse(response);
        }),
      );
  }

  getCurrentUser(): Observable<UserProfile> {
    return this.http
      .get<unknown>(`${this.baseUrl}/api/web/user/profile`, {
        headers: this.authorizationHeaders(),
        transferCache: false,
      })
      .pipe(
        map((response) => UserProfileSchema.parse(response)),
        catchError((error: unknown) => this.userApiError(error)),
      );
  }

  getStatistics(days: ReportingPeriod): Observable<UsageStats> {
    return this.http
      .get<unknown>(`${this.baseUrl}/api/web/user/stats`, {
        params: { days },
        headers: this.authorizationHeaders(),
      })
      .pipe(
        map((response) => UsageStatsSchema.parse(response)),
        catchError((error: unknown) => this.userApiError(error)),
      );
  }

  deleteMessage(messageUrl: string): Observable<DeletedMessage> {
    return this.http
      .post<unknown>(
        `${this.baseUrl}/api/web/user/messages/delete`,
        {
          message_url: messageUrl,
        },
        {
          headers: this.authorizationHeaders(),
          transferCache: false,
        },
      )
      .pipe(
        map((response) => DeletedMessageSchema.parse(response)),
        catchError((error: unknown) => this.userApiError(error)),
      );
  }

  private authorizationHeaders(): Record<string, string> {
    const token = this.session.accessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  private userApiError(error: unknown): Observable<never> {
    if (error instanceof HttpErrorResponse && error.status === 401) this.session.signOut();
    return throwError(() => error);
  }
}
