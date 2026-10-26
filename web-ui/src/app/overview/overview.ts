import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { ReportingPeriod, ReportingPeriodSchema } from '../api/api.schemas';
import { ApiService } from '../api/api.service';
import { AgentIntro } from './agent-intro';

@Component({
  selector: 'sk-overview',
  imports: [AgentIntro, DecimalPipe],
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  private readonly api = inject(ApiService);
  protected readonly period = signal<ReportingPeriod>('7');
  protected readonly tokenFormatter = new Intl.NumberFormat('en', {
    notation: 'compact',
    maximumFractionDigits: 1,
  });
  protected readonly stats = rxResource({
    params: () => this.period(),
    stream: ({ params }) => this.api.getStatistics(params),
  });

  protected changePeriod(event: Event): void {
    const result = ReportingPeriodSchema.safeParse((event.target as HTMLSelectElement).value);
    if (result.success) this.period.set(result.data);
  }
}
