import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { routes } from '../app.routes';
import { API_BASE_URL } from '../api/api-base-url';
import { AuthSession } from './auth-session';
import { webcrypto } from 'node:crypto';
import { BrowserLogin } from './browser-login';
import { GoogleSignIn } from './google-sign-in';
import { authTestIds } from './auth-test-ids';

describe('Google sign-in', () => {
  beforeEach(() => {
    vi.stubGlobal('crypto', webcrypto);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        { provide: API_BASE_URL, useValue: 'https://backend.test' },
      ],
    });
    sessionStorage.clear();
    history.replaceState(null, '', '/login');
  });
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    sessionStorage.clear();
    history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  });

  async function callback() {
    const url = new URL(await TestBed.inject(BrowserLogin).authorizationUrl());
    const state = url.searchParams.get('sidekick_state');
    history.replaceState(null, '', `/login?code=one-use-handoff&state=${state}`);
    const fixture = TestBed.createComponent(GoogleSignIn);
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/auth/config')
      .flush({ enabled: true });
    await new Promise<void>((resolve) => queueMicrotask(resolve));
    return fixture;
  }

  it('exchanges the callback handoff and sends only the Sidekick bearer on subsequent API requests', async () => {
    const fixture = await callback();
    const exchange = TestBed.inject(HttpTestingController).expectOne(
      'https://backend.test/api/web/auth/token',
    );
    fixture.detectChanges();
    const statuses = (fixture.nativeElement as HTMLElement).querySelectorAll('[role="status"]');
    expect(Array.from(statuses, (status) => status.textContent?.trim())).toEqual(['Signing in…']);
    expect(exchange.request.method).toBe('POST');
    expect(exchange.request.body.code).toBe('one-use-handoff');
    expect(exchange.request.body.code_verifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    exchange.flush({
      access_token: 'sidekick-jwt-fixture',
      token_type: 'Bearer',
      expires_at: new Date(Date.now() + 900000).toISOString(),
    });
    const profile = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne('https://backend.test/api/web/user/profile'),
    );
    expect(profile.request.headers.get('Authorization')).toBe('Bearer sidekick-jwt-fixture');
    profile.flush({ email: 'identity-from-profile@example.com' });
    await fixture.whenStable();
    expect(TestBed.inject(AuthSession).email()).toBe('identity-from-profile@example.com');
    expect(window.location.search).toBe('');
    expect(sessionStorage.length).toBe(0);
  });

  it('rejects a callback whose state does not match the initiating tab', async () => {
    await TestBed.inject(BrowserLogin).authorizationUrl();
    history.replaceState(null, '', '/login?code=stolen-handoff&state=wrong');
    const fixture = TestBed.createComponent(GoogleSignIn);
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/auth/config')
      .flush({ enabled: true });
    await fixture.whenStable();

    TestBed.inject(HttpTestingController).expectNone('https://backend.test/api/web/auth/token');
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('invalid or expired');
    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
  });

  it('restores a remembered browser on reload and learns identity only from the profile API', async () => {
    const fixture = TestBed.createComponent(GoogleSignIn);
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('https://backend.test/api/web/auth/config').flush({ enabled: true });
    const restore = await vi.waitFor(() =>
      http.expectOne({ method: 'GET', url: 'https://backend.test/api/web/auth/session' }),
    );
    expect(restore.request.withCredentials).toBe(true);
    expect(restore.request.transferCache).toBe(false);
    expect(restore.request.headers.get('X-Sidekick-Session')).toBe('1');

    restore.flush({
      access_token: 'restored-opaque-token',
      token_type: 'Bearer',
      expires_at: new Date(Date.now() + 86400000).toISOString(),
    });
    const profile = await vi.waitFor(() =>
      http.expectOne('https://backend.test/api/web/user/profile'),
    );
    expect(TestBed.inject(AuthSession).email()).toBeNull();
    expect(profile.request.headers.get('Authorization')).toBe('Bearer restored-opaque-token');
    expect(profile.request.withCredentials).toBe(false);
    profile.flush({ email: 'remembered@example.com' });
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/app/overview');
    expect(TestBed.inject(AuthSession).email()).toBe('remembered@example.com');
    http.expectNone('https://backend.test/api/web/auth/token');
  });

  it('shows Google sign-in when the remembered browser cookie has expired', async () => {
    const fixture = TestBed.createComponent(GoogleSignIn);
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('https://backend.test/api/web/auth/config').flush({ enabled: true });
    const restore = await vi.waitFor(() =>
      http.expectOne('https://backend.test/api/web/auth/session'),
    );

    restore.flush({}, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        page.querySelector<HTMLButtonElement>(`[data-testid="${authTestIds.googleButton}"]`)
          ?.disabled,
      ).toBe(false);
    });
    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(TestBed.inject(AuthSession).email()).toBeNull();
    http.expectNone('https://backend.test/api/web/user/profile');
  });

  it('shows a domain rejection returned by the Google callback', async () => {
    history.replaceState(null, '', '/login?error=domain');
    const fixture = TestBed.createComponent(GoogleSignIn);
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/auth/config')
      .flush({ enabled: true });
    await fixture.whenStable();

    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('email domain is not allowed');
    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
  });

  it('rejects a malformed token response without signing the user in', async () => {
    const fixture = await callback();
    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/auth/token')
      .flush({
        access_token: 'sidekick-jwt-fixture',
        token_type: 'Bearer',
        expires_at: 'not-a-date',
      });
    await fixture.whenStable();

    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('Couldn’t complete sign-in');
  });

  it('returns to login if the profile API rejects the exchanged access token', async () => {
    const fixture = await callback();
    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/auth/token')
      .flush({
        access_token: 'rejected-access-token',
        token_type: 'Bearer',
        expires_at: new Date(Date.now() + 900000).toISOString(),
      });
    const profile = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne('https://backend.test/api/web/user/profile'),
    );
    profile.flush({}, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/login');
    expect(TestBed.inject(AuthSession).email()).toBeNull();
    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
  });
});
