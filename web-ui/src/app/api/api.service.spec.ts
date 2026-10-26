import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { $ZodError } from 'zod/v4/core';
import { environment } from '../../environments/environment';
import { testStats } from '../testing/stats-api';
import { ApiService } from './api.service';

describe('ApiService', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('returns a validated profile with the configured identity', async () => {
    const response = firstValueFrom(TestBed.inject(ApiService).getProfile());
    const profile = {
      name: 'Juniper',
      slack: { username: 'juniper-helper', user_id: 'UBOT', team_id: 'TTEAM' },
    };
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush(profile);
    expect(await response).toEqual(profile);
  });

  it('accepts an explicitly disconnected Slack profile', async () => {
    const response = firstValueFrom(TestBed.inject(ApiService).getProfile());
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush({ name: 'Juniper', slack: null });
    expect(await response).toEqual({ name: 'Juniper', slack: null });
  });

  it.each([
    { label: 'missing name', body: { slack: null } },
    { label: 'blank name', body: { name: ' ', slack: null } },
    { label: 'missing Slack identity', body: { name: 'Juniper' } },
    {
      label: 'invalid Slack identity',
      body: { name: 'Juniper', slack: { username: 123, user_id: 'UBOT', team_id: 'TTEAM' } },
    },
    { label: 'unexpected fields', body: { name: 'Juniper', slack: null, unexpected: true } },
  ])('rejects profile responses with $label', async ({ body }) => {
    const response = firstValueFrom(TestBed.inject(ApiService).getProfile());
    const rejected = expect(response).rejects.toBeInstanceOf($ZodError);
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/profile`)
      .flush(body);
    await rejected;
  });

  it('requests the selected reporting period and validates the returned counts', async () => {
    const response = firstValueFrom(TestBed.inject(ApiService).getStatistics('30'));
    const body = { ...testStats, period_days: 30 };
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/stats?days=30`)
      .flush(body);
    expect(await response).toEqual(body);
  });

  it.each([
    { label: 'a string count', body: { ...testStats, users: '3' } },
    { label: 'a negative count', body: { ...testStats, conversations: -1 } },
    { label: 'a fractional count', body: { ...testStats, channels: 1.5 } },
    {
      label: 'an unsafe integer',
      body: { ...testStats, tokens_consumed: Number.MAX_SAFE_INTEGER + 1 },
    },
    { label: 'an unsupported reporting period', body: { ...testStats, period_days: 14 } },
    { label: 'an unexpected field', body: { ...testStats, unexpected: true } },
    { label: 'missing fields', body: { users: 1 } },
  ])('rejects statistics with $label', async ({ body }) => {
    const response = firstValueFrom(TestBed.inject(ApiService).getStatistics('7'));
    const rejected = expect(response).rejects.toBeInstanceOf($ZodError);
    TestBed.inject(HttpTestingController)
      .expectOne(`${environment.apiBaseUrl}/api/web/user/stats?days=7`)
      .flush(body);
    await rejected;
  });
});
