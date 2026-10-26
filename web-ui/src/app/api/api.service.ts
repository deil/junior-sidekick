import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AgentProfile,
  AgentProfileSchema,
  ReportingPeriod,
  UsageStats,
  UsageStatsSchema,
} from './api.schemas';

/** The only HTTP boundary. Every response is unknown until its Zod schema accepts it. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  getProfile(): Observable<AgentProfile> {
    return this.http
      .get<unknown>(`${environment.apiBaseUrl}/api/web/user/profile`)
      .pipe(map((response) => AgentProfileSchema.parse(response)));
  }

  getStatistics(days: ReportingPeriod): Observable<UsageStats> {
    return this.http
      .get<unknown>(`${environment.apiBaseUrl}/api/web/user/stats`, { params: { days } })
      .pipe(map((response) => UsageStatsSchema.parse(response)));
  }
}
