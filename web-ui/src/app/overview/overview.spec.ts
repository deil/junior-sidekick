import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AgentProfileStore } from '../agent-profile';
import { testStats } from '../testing/stats-api';
import { environment } from '../../environments/environment';
import { Overview } from './overview';

describe('Overview', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AgentProfileStore).profile.set({
      name: 'Juniper',
      slack: {
        username: 'juniper_helper',
        user_id: 'UBOT',
        team_id: 'TTEAM',
      },
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify({ ignoreCancelled: true }));

  function createOverview() {
    const fixture = TestBed.createComponent(Overview);
    TestBed.tick();
    return fixture;
  }

  function request(days = '7') {
    return TestBed.inject(HttpTestingController).expectOne(
      `${environment.apiBaseUrl}/api/web/user/stats?days=${days}`,
    );
  }

  it('uses the API profile and real statistics without sample data', async () => {
    const fixture = createOverview();
    request().flush(testStats);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('#intro-title')?.textContent).toContain('Hi, I’m Juniper');
    expect(page.querySelector('.slack-handle')?.textContent).toBe('@juniper_helper');
    expect(page.querySelector('.thread-reply .message-author')?.textContent).toBe('Juniper');
    expect(page.querySelector('.slack-identity a, .slack-identity button')).toBeNull();
    expect(page.textContent).not.toMatch(/\bDMs?\b/);
    expect([...page.querySelectorAll('.stat dd')].map((stat) => stat.textContent?.trim())).toEqual([
      '3',
      '9',
      '1.5K',
    ]);
    expect(page.textContent).not.toContain('Sample numbers');
    expect(page.querySelector('.token-stat dt')?.textContent).toBe('burned tokens');
    expect(page.querySelector('.token-stat dd')?.getAttribute('aria-label')).toBe('1542 tokens');
    expect(page.textContent?.toLowerCase()).not.toContain('sidekick');
  });

  it('loads a new aggregate when the reporting period changes', async () => {
    const fixture = createOverview();
    request().flush(testStats);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const select = page.querySelector('select')!;

    select.value = '30';
    select.dispatchEvent(new Event('change'));
    TestBed.tick();
    request('30').flush({
      ...testStats,
      period_days: 30,
      users: 12,
      conversations: 1234,
      tokens_consumed: 1234567,
    });
    await fixture.whenStable();

    expect([...page.querySelectorAll('.stat dd')].map((stat) => stat.textContent?.trim())).toEqual([
      '12',
      '1,234',
      '1.2M',
    ]);
  });

  it('shows loading instead of invented numbers and renders real zero counts', async () => {
    const fixture = createOverview();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.activity-status')?.textContent).toContain('Loading activity');
    expect(page.querySelector('.stats')).toBeNull();

    request().flush({ ...testStats, users: 0, conversations: 0, tokens_consumed: 0 });
    await fixture.whenStable();
    expect([...page.querySelectorAll('.stat dd')].map((stat) => stat.textContent?.trim())).toEqual([
      '0',
      '0',
      '0',
    ]);
  });

  it('shows an error and retries the API when activity cannot be loaded', async () => {
    const fixture = createOverview();
    request().flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.activity-status')?.textContent).toContain('Couldn’t load activity');
    expect(page.querySelector('.stats')).toBeNull();

    page.querySelector<HTMLButtonElement>('.activity-status button')!.click();
    TestBed.tick();
    request().flush(testStats);
    await fixture.whenStable();
    expect(page.querySelector('.stat dd')?.textContent).toBe('3');
  });

  it('uses the latest period when filters are changed before a response arrives', async () => {
    const fixture = createOverview();
    const pendingWeek = request();
    const select = (fixture.nativeElement as HTMLElement).querySelector('select')!;

    select.value = '30';
    select.dispatchEvent(new Event('change'));
    TestBed.tick();

    expect(pendingWeek.cancelled).toBe(true);
    request('30').flush({ ...testStats, period_days: 30, users: 12 });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('.stat dd')?.textContent).toBe(
      '12',
    );
  });

  it('treats a malformed API response as an error instead of displaying invalid counts', async () => {
    const fixture = createOverview();
    request().flush({ ...testStats, users: 'not a count' });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.stats')).toBeNull();
    expect(page.querySelector('.activity-status')?.textContent).toContain('Couldn’t load activity');
  });
});
