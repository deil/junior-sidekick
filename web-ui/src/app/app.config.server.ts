import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { API_BASE_URL } from './api/api-base-url';
import { serverApiBaseUrl } from './api/api-base-url.server';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    { provide: API_BASE_URL, useFactory: serverApiBaseUrl },
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
