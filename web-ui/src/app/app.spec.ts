import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { NavigationEnd, provideRouter, Router, TitleStrategy } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { AgentTitleStrategy } from './agent-title-strategy';
import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { statsApiStub } from './testing/stats-api';
import { AgentProfileStore } from './agent-profile';
import { API_BASE_URL } from './api/api-base-url';
import { environment } from '../environments/environment';
import { AuthSession } from './auth/auth-session';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authTestIds } from './auth/auth-test-ids';
import { killSwitchTestIds } from './kill-switch/kill-switch-test-ids';
import { filter, firstValueFrom } from 'rxjs';
import { ApiService } from './api/api.service';
import { testStats } from './testing/stats-api';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([statsApiStub])),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: environment.apiBaseUrl },
        { provide: TitleStrategy, useClass: AgentTitleStrategy },
      ],
    }).compileComponents();
    TestBed.inject(AgentProfileStore).profile.set({ name: 'Juniper', slack: null });
    TestBed.inject(AuthSession).accept({
      access_token: 'fixture-token',
      token_type: 'Bearer',
      expires_at: new Date(Date.now() + 900000).toISOString(),
    });
    TestBed.inject(AuthSession).identify({ email: 'person@example.com' });
  });

  it('takes signed-out visitors to sign-in instead of displaying the workspace', async () => {
    TestBed.inject(AuthSession).signOut();
    const fixture = TestBed.createComponent(App);

    await TestBed.inject(Router).navigateByUrl('/app/overview');
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/auth/config`)
      .flush({ enabled: false });
    await fixture.whenStable();
    fixture.detectChanges();

    const page = fixture.nativeElement as HTMLElement;
    expect(TestBed.inject(Router).url).toBe('/login');
    expect(page.querySelector('h1')?.textContent).toContain('Sign in to Juniper');
    expect(page.querySelector('nav')).toBeNull();
    expect(page.querySelector(`[data-testid="${killSwitchTestIds.openButton}"]`)).toBeNull();
    expect(page.querySelector('.stats')).toBeNull();
  });

  it('provides separate overview and administration navigation', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('a[href="/app/overview"]')).toBeTruthy();
    expect(compiled.querySelector('a[href="/admin"]')).toBeTruthy();
    expect(compiled.querySelector('a[href="#main-content"]')).toBeTruthy();
    expect(compiled.querySelector('.brand')?.textContent).toContain('Juniper');
    expect(compiled.textContent?.toLowerCase()).not.toContain('sidekick');
  });

  it('shows the signed-in account in the sidebar footer', async () => {
    const fixture = TestBed.createComponent(App);

    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(
      page.querySelector(`.sidebar-footer [data-testid="${authTestIds.currentUser}"]`)?.textContent,
    ).toBe('person@example.com');
    expect(
      page
        .querySelector<HTMLButtonElement>(`[data-testid="${authTestIds.accountSignOutButton}"]`)
        ?.textContent?.trim(),
    ).toBe('Sign out');
  });

  it('removes the current account and returns to sign-in when signing out', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/app/overview');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    page.querySelector<HTMLButtonElement>('.sidebar-toggle')?.click();
    await fixture.whenStable();
    const navigated = firstValueFrom(
      TestBed.inject(Router).events.pipe(
        filter((event) => event instanceof NavigationEnd && event.urlAfterRedirects === '/login'),
      ),
    );

    page
      .querySelector<HTMLButtonElement>(`[data-testid="${authTestIds.accountSignOutButton}"]`)
      ?.click();
    TestBed.inject(HttpTestingController)
      .expectOne({ method: 'DELETE', url: `${environment.apiBaseUrl}/api/web/auth/session` })
      .flush({ signedOut: true });
    TestBed.tick();
    await navigated;
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/auth/config`)
      .flush({ enabled: true });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/login');
    expect(page.querySelector(`[data-testid="${authTestIds.currentUser}"]`)).toBeNull();
    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
  });

  it('starts collapsed and lets users expand and collapse the sidebar', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const toggle = page.querySelector<HTMLButtonElement>('.sidebar-toggle')!;
    expect(toggle.closest('aside')?.id).toBe('workspace-sidebar');

    expect(page.classList.contains('sidebar-collapsed')).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.getAttribute('aria-label')).toBe('Expand sidebar');
    expect(page.querySelector('nav a[aria-label="Overview"]')).toBeTruthy();

    toggle.click();
    await fixture.whenStable();
    expect(page.classList.contains('sidebar-collapsed')).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Collapse sidebar');

    toggle.click();
    await fixture.whenStable();
    expect(page.classList.contains('sidebar-collapsed')).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('omits breadcrumbs on the personal overview but keeps them on admin pages', async () => {
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/app/overview');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.topbar')).toBeNull();
    expect(
      page.querySelector(`aside [data-testid="${killSwitchTestIds.openButton}"]`),
    ).toBeTruthy();
    expect(TestBed.inject(Title).getTitle()).toBe('Overview · Juniper');

    await router.navigateByUrl('/admin');
    await fixture.whenStable();
    expect(page.querySelector('.topbar')?.textContent).toContain('Juniper');
    expect(
      page.querySelector(`aside [data-testid="${killSwitchTestIds.openButton}"]`),
    ).toBeTruthy();
    expect(TestBed.inject(Title).getTitle()).toBe('Administration · Juniper');
  });
});

describe('Expired API authentication', () => {
  it('clears the account and shows login when a later statistics request returns 401', async () => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: environment.apiBaseUrl },
      ],
    });
    const session = TestBed.inject(AuthSession);
    session.accept({
      access_token: 'opaque-access-token',
      token_type: 'Bearer',
      expires_at: new Date(Date.now() + 900000).toISOString(),
    });
    TestBed.inject(AgentProfileStore).profile.set({ name: 'Juniper', slack: null });
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    const http = TestBed.inject(HttpTestingController);
    const navigation = router.navigateByUrl('/app/overview');
    const profile = await vi.waitFor(() =>
      http.expectOne(`${environment.apiBaseUrl}/api/web/user/profile`),
    );
    profile.flush({ email: 'person@example.com' });
    await navigation;
    TestBed.tick();
    http.expectOne(`${environment.apiBaseUrl}/api/web/user/stats?days=7`).flush(testStats);
    await fixture.whenStable();
    const request = firstValueFrom(TestBed.inject(ApiService).getStatistics('30'));
    const rejected = expect(request).rejects.toMatchObject({ status: 401 });

    http
      .expectOne(`${environment.apiBaseUrl}/api/web/user/stats?days=30`)
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    await rejected;
    TestBed.tick();
    const config = await vi.waitFor(() =>
      http.expectOne(`${environment.apiBaseUrl}/api/web/auth/config`),
    );
    config.flush({ enabled: true });
    await fixture.whenStable();

    expect(router.url).toBe('/login');
    expect(session.email()).toBeNull();
    expect(session.accessToken()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toContain(
      'Sign in to Juniper',
    );
    http.verify();
  });
});
