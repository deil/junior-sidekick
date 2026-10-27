import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AgentProfileStore } from './agent-profile';
import { environment } from '../environments/environment';
import { API_BASE_URL } from './api/api-base-url';

describe('AgentProfileStore', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: environment.apiBaseUrl },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads the configured name and Slack identity from the dedicated API', async () => {
    const store = TestBed.inject(AgentProfileStore);
    const loading = store.load();
    const profile = {
      name: 'Juniper',
      slack: { username: 'juniper_helper', user_id: 'UBOT', team_id: 'TTEAM' },
    };
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush(profile);
    await loading;
    expect(store.profile()).toEqual(profile);
    expect(store.failed()).toBe(false);
  });

  it('allows retry after an API error without substituting a name', async () => {
    const store = TestBed.inject(AgentProfileStore);
    const loading = store.load();
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    await loading;
    expect(store.profile()).toBeNull();
    expect(store.failed()).toBe(true);

    const retry = store.load();
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush({ name: 'Fern', slack: null });
    await retry;
    expect(store.profile()?.name).toBe('Fern');
    expect(store.failed()).toBe(false);
  });

  it('does not expose a profile rejected by the API response schema', async () => {
    const store = TestBed.inject(AgentProfileStore);
    const loading = store.load();
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush({ name: 123, slack: null });
    await loading;
    expect(store.profile()).toBeNull();
    expect(store.failed()).toBe(true);
  });
});
