import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  PLATFORM_ID,
  REQUEST,
} from '@angular/core';
import { isPlatformServer } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { AgentProfileStore } from './agent-profile';
import { provideRouter, TitleStrategy } from '@angular/router';
import { routes } from './app.routes';
import { provideClientHydration } from '@angular/platform-browser';
import { AgentTitleStrategy } from './agent-title-strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(),
    provideAppInitializer(() => {
      // Route extraction has no HTTP request and must not load deployment data.
      if (isPlatformServer(inject(PLATFORM_ID)) && !inject(REQUEST)) {
        return;
      }
      return inject(AgentProfileStore).load();
    }),
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: TitleStrategy, useClass: AgentTitleStrategy },
    provideClientHydration(),
  ],
};
