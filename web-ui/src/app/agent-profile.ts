import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api/api.service';
import { AgentProfile } from './api/api.schemas';

@Injectable({ providedIn: 'root' })
export class AgentProfileStore {
  private readonly api = inject(ApiService);
  readonly profile = signal<AgentProfile | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

  async load(): Promise<void> {
    this.loading.set(true);
    this.failed.set(false);
    try {
      const profile = await firstValueFrom(this.api.getProfile());
      this.profile.set(profile);
    } catch {
      this.failed.set(true);
    } finally {
      this.loading.set(false);
    }
  }
}
