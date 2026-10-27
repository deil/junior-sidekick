import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { MissingApiConfigurationError } from './app/api/api-base-url';

bootstrapApplication(App, appConfig).catch((err: unknown) => {
  console.error(err);
  if (err instanceof MissingApiConfigurationError) {
    const heading = document.createElement('h1');
    heading.textContent = 'Workspace configuration is missing.';
    const message = document.createElement('p');
    message.textContent = 'Serve this page through the SSR server, then reload.';
    document.querySelector('app-root')?.replaceChildren(heading, message);
  }
});
