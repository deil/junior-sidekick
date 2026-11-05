import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { UsageStats } from '../api/api.schemas';

export const testStats: UsageStats = {
  period_days: 7,
  users: 3,
  conversations: 9,
  channels: 4,
  tokens_consumed: 1542,
};

/** API-boundary stub for shell/router tests; overview tests exercise requests directly. */
export const statsApiStub: HttpInterceptorFn = (request, next) => {
  if (request.url.endsWith('/api/web/user/profile')) {
    return request.headers.has('Authorization')
      ? of(new HttpResponse({ body: { email: 'person@example.com' } }))
      : throwError(() => new HttpErrorResponse({ status: 401 }));
  }
  return request.url.endsWith('/api/web/user/stats')
    ? of(new HttpResponse({ body: testStats }))
    : next(request);
};
