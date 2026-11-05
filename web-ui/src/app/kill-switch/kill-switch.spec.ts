import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthSession } from '../auth/auth-session';
import { API_BASE_URL } from '../api/api-base-url';
import { KillSwitch } from './kill-switch';
import { killSwitchTestIds as ids } from './kill-switch-test-ids';

describe('Kill switch', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: 'https://backend.test' },
      ],
    });
    TestBed.inject(AuthSession).accept({
      access_token: 'opaque-sidekick-token',
      token_type: 'Bearer',
      expires_at: new Date(Date.now() + 86400000).toISOString(),
    });
    TestBed.inject(AuthSession).identify({ email: 'operator@example.com' });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const fixture = TestBed.createComponent(KillSwitch);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    const input = page.querySelector<HTMLInputElement>(`[data-testid="${ids.messageUrl}"]`);
    const form = page.querySelector<HTMLFormElement>(`[data-testid="${ids.form}"]`);
    if (!input || !form) throw new Error('Kill switch form is missing');
    input.value = 'https://workspace.slack.com/archives/C123ABC/p1700000000001234';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    return { fixture, page, input, form };
  }

  it('deletes the pasted link with a bearer token and confirms deletion only after the API responds', async () => {
    const { fixture, page, input, form } = setup();

    form.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    const request = TestBed.inject(HttpTestingController).expectOne(
      'https://backend.test/api/web/user/messages/delete',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ message_url: input.value });
    expect(request.request.headers.get('Authorization')).toBe('Bearer opaque-sidekick-token');
    expect(request.request.withCredentials).toBe(false);
    expect(
      page.querySelector<HTMLButtonElement>(`[data-testid="${ids.deleteButton}"]`)?.disabled,
    ).toBe(true);
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Deleting');
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.inject(HttpTestingController).expectNone(
      'https://backend.test/api/web/user/messages/delete',
    );
    request.flush({ status: 'deleted' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(page.querySelector('[role="status"]')?.textContent).toBe('Message deleted from Slack.');
    expect(input.value).toBe('');
  });

  it.each([
    { status: 400, message: 'Paste a Slack message link' },
    { status: 403, message: 'only delete its own messages' },
    { status: 404, message: 'Message not found' },
    { status: 429, message: 'Slack is limiting requests' },
    { status: 503, message: 'Couldn’t confirm deletion' },
  ])(
    'keeps the link and reports HTTP $status without claiming deletion',
    async ({ status, message }) => {
      const { fixture, page, input, form } = setup();
      form.dispatchEvent(new Event('submit', { cancelable: true }));

      TestBed.inject(HttpTestingController)
        .expectOne('https://backend.test/api/web/user/messages/delete')
        .flush({}, { status, statusText: 'Rejected' });
      await fixture.whenStable();
      fixture.detectChanges();

      expect(page.querySelector('[role="alert"]')?.textContent).toContain(message);
      expect(page.querySelector('[role="status"]')).toBeNull();
      expect(input.value).toBe('https://workspace.slack.com/archives/C123ABC/p1700000000001234');
    },
  );

  it('clears authentication when the deletion API returns 401', async () => {
    const { fixture, page, form } = setup();
    form.dispatchEvent(new Event('submit', { cancelable: true }));

    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/user/messages/delete')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(TestBed.inject(AuthSession).email()).toBeNull();
    expect(TestBed.inject(AuthSession).accessToken()).toBeNull();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Sign in again');
  });

  it('does not report deletion for a malformed success response', async () => {
    const { fixture, page, form } = setup();
    form.dispatchEvent(new Event('submit', { cancelable: true }));

    TestBed.inject(HttpTestingController)
      .expectOne('https://backend.test/api/web/user/messages/delete')
      .flush({ status: 'not-deleted' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain(
      'Couldn’t confirm deletion',
    );
    expect(page.querySelector('[role="status"]')).toBeNull();
  });
});
