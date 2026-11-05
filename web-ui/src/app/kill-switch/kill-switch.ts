import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../api/api.service';
import { killSwitchTestIds } from './kill-switch-test-ids';

@Component({
  selector: 'sk-kill-switch',
  templateUrl: './kill-switch.html',
  styleUrl: './kill-switch.css',
  host: { '[class.collapsed]': 'collapsed()' },
})
export class KillSwitch {
  readonly collapsed = input(false);
  private readonly api = inject(ApiService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly ids = killSwitchTestIds;
  protected readonly messageUrl = signal('');
  protected readonly deleting = signal(false);
  protected readonly deleted = signal(false);
  protected readonly error = signal<string | null>(null);

  protected open(): void {
    this.dialog().nativeElement.showModal();
  }

  protected close(): void {
    this.dialog().nativeElement.close();
  }

  protected updateUrl(event: Event): void {
    this.messageUrl.set((event.target as HTMLInputElement).value);
    this.deleted.set(false);
    this.error.set(null);
  }

  protected async deleteMessage(event: Event): Promise<void> {
    event.preventDefault();
    const url = this.messageUrl().trim();
    if (this.deleting() || !url) return;

    this.deleting.set(true);
    this.deleted.set(false);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.deleteMessage(url));
      this.messageUrl.set('');
      this.deleted.set(true);
    } catch (error: unknown) {
      this.error.set(deletionError(error));
    } finally {
      this.deleting.set(false);
    }
  }
}

function deletionError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    switch (error.status) {
      case 400:
        return 'Paste a Slack message link copied from the message’s menu.';
      case 401:
        return 'Sign in again to delete a message.';
      case 403:
        return 'The bot cannot delete this message. It can only delete its own messages.';
      case 404:
        return 'Message not found. Check the link and that the bot can access the channel.';
      case 429:
        return 'Slack is limiting requests. Wait a moment and try again.';
    }
  }
  return 'Couldn’t confirm deletion. Check Slack before trying again.';
}
