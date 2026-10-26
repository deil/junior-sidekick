import { Component, inject } from '@angular/core';
import { AgentProfileStore } from '../agent-profile';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'sk-admin',
  imports: [RouterLink],
  template: `
    <p class="eyebrow">WORKSPACE ADMINISTRATION</p>
    <h1>Behind the scenes.</h1>
    <p class="lead">
      The place to configure {{ profile.profile()?.name }} and understand how it works.
    </p>
    <p class="notice">
      Interface preview. Configuration and admin operations are not connected yet.
    </p>
    <div class="admin-grid">
      @for (area of areas; track area.title) {
        <section>
          <span>PLANNED</span>
          <h2>{{ area.title }}</h2>
          <p>{{ area.description }}</p>
        </section>
      }
    </div>
    <a routerLink="/app/overview">Back to the overview →</a>
  `,
  styles: `
    :host {
      display: block;
    }
    h1 {
      font: 3rem var(--font-display);
      margin: 12px 0;
    }
    .lead {
      color: var(--muted);
    }
    .notice {
      padding: 18px;
      background: var(--accent-soft);
      border-radius: 10px;
      margin: 32px 0;
    }
    .admin-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 20px;
      margin-bottom: 32px;
    }
    section {
      border: 1px solid var(--border);
      background: var(--surface);
      padding: 28px;
      border-radius: 12px;
    }
    section span {
      font: 10px var(--font-mono);
      color: var(--muted);
      letter-spacing: 0.1em;
    }
    h2 {
      font-size: 19px;
    }
    section p {
      color: var(--muted);
      line-height: 1.7;
      font-size: 14px;
    }
    a {
      color: var(--accent);
    }
    @media (max-width: 600px) {
      .admin-grid {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class Admin {
  protected readonly profile = inject(AgentProfileStore);
  protected readonly areas = [
    {
      title: 'Tools & MCP servers',
      description: 'Connect external tools and manage the available MCP servers.',
    },
    {
      title: 'Skills & sources',
      description: 'Manage skill sources and discover reusable capabilities.',
    },
    {
      title: 'Knowledge & company context',
      description: 'Manage the shared context your teammate needs to work with your team.',
    },
    {
      title: 'Sessions & turns',
      description: 'Inspect conversations, model calls, and tool activity to troubleshoot issues.',
    },
  ];
}
