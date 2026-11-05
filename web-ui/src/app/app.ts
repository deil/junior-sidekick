import { Component, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { Icon } from './shared/icon';
import { AgentProfileStore } from './agent-profile';
import { AuthSession } from './auth/auth-session';
import { CurrentAccount } from './auth/current-account';
import { KillSwitch } from './kill-switch/kill-switch';

@Component({
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon, CurrentAccount, KillSwitch],
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
    { initialValue: null },
  );
  protected readonly showBreadcrumbs = computed(() => {
    const path = (this.currentUrl() ?? this.router.url).split(/[?#]/)[0];
    return path !== '/' && path !== '/app' && path !== '/app/overview';
  });
  protected readonly profile = inject(AgentProfileStore);
  protected readonly auth = inject(AuthSession);
  protected readonly signedInProfile = computed(() =>
    this.auth.email() ? this.profile.profile() : null,
  );
  protected readonly sidebarCollapsed = signal(true);

  constructor() {
    effect(() => {
      const url = this.currentUrl();
      if (url && !this.auth.email() && !url.startsWith('/login')) {
        void this.router.navigateByUrl('/login', { replaceUrl: true });
      }
    });
  }
}
