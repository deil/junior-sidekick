import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { provideRouter, Router, TitleStrategy } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { AgentTitleStrategy } from './agent-title-strategy';
import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { statsApiStub } from './testing/stats-api';
import { AgentProfileStore } from './agent-profile';
import { API_BASE_URL } from './api/api-base-url';
import { environment } from '../environments/environment';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([statsApiStub])),
        { provide: API_BASE_URL, useValue: environment.apiBaseUrl },
        { provide: TitleStrategy, useClass: AgentTitleStrategy },
      ],
    }).compileComponents();
    TestBed.inject(AgentProfileStore).profile.set({ name: 'Juniper', slack: null });
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
    expect(TestBed.inject(Title).getTitle()).toBe('Overview · Juniper');

    await router.navigateByUrl('/admin');
    await fixture.whenStable();
    expect(page.querySelector('.topbar')?.textContent).toContain('Juniper');
    expect(TestBed.inject(Title).getTitle()).toBe('Administration · Juniper');
  });
});
