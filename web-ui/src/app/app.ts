import { Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { Icon } from './shared/icon';
import { AgentProfileStore } from './agent-profile';

@Component({
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
  host: { '[class.sidebar-collapsed]': 'sidebarCollapsed()' },
})
export class App {
  private readonly router = inject(Router);
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  protected readonly showBreadcrumbs = computed(() => {
    const path = this.currentUrl().split(/[?#]/)[0];
    return path !== '/' && path !== '/app' && path !== '/app/overview';
  });
  protected readonly profile = inject(AgentProfileStore);
  protected readonly sidebarCollapsed = signal(true);
}
