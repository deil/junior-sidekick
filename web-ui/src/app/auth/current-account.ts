import { Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthSession } from './auth-session';
import { authTestIds } from './auth-test-ids';
import { BrowserSession } from './browser-session';

@Component({
  selector: 'sk-current-account',
  imports: [RouterLink],
  host: { role: 'region', 'aria-label': 'Signed-in account', '[class.collapsed]': 'collapsed()' },
  template: `
    @if (session.email(); as email) {
      <a
        class="avatar"
        routerLink="/login"
        [title]="email"
        [attr.aria-label]="'Account: ' + email"
        [attr.data-testid]="ids.accountLink"
        ><span aria-hidden="true">{{ email.charAt(0).toUpperCase() }}</span></a
      >
      <div class="details">
        <span class="email" [title]="email" [attr.data-testid]="ids.currentUser">{{ email }}</span>
        <button
          type="button"
          [attr.data-testid]="ids.accountSignOutButton"
          (click)="browserSession.signOut()"
          [disabled]="browserSession.signingOut()"
        >
          Sign out
        </button>
      </div>
      @if (browserSession.error(); as message) {
        <p role="alert">{{ message }}</p>
      }
    }
  `,
  styleUrl: './current-account.css',
})
export class CurrentAccount {
  readonly collapsed = input(false);
  protected readonly session = inject(AuthSession);
  protected readonly browserSession = inject(BrowserSession);
  protected readonly ids = authTestIds;
}
