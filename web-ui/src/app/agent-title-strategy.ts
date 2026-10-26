import { effect, inject, Injectable, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { AgentProfileStore } from './agent-profile';

@Injectable()
export class AgentTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly profile = inject(AgentProfileStore);
  private readonly page = signal<string | undefined>(undefined);

  constructor() {
    super();
    effect(() => {
      const name = this.profile.profile()?.name;
      const page = this.page();
      this.title.setTitle(name ? (page ? `${page} · ${name}` : name) : 'Workspace');
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.page.set(this.buildTitle(snapshot));
  }
}
