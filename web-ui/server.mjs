import express from 'express';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const configuredUrl = process.env.API_BASE_URL?.trim();
if (!configuredUrl)
  throw new Error('API_BASE_URL is required. Set it before starting the web server.');
const apiUrl = new URL(configuredUrl);
if (apiUrl.protocol !== 'http:' && apiUrl.protocol !== 'https:') {
  throw new Error('API_BASE_URL must be an absolute HTTP or HTTPS URL.');
}
const apiBaseUrl = configuredUrl.replace(/\/+$/, '');
const browserDistFolder = fileURLToPath(new URL('./dist/web-ui/browser/', import.meta.url));
const template = readFileSync(new URL('./dist/web-ui/browser/index.html', import.meta.url), 'utf8');
const html = template.replace(
  '</head>',
  `<meta name="api-base-url" content="${encodeURIComponent(apiBaseUrl)}"></head>`,
);

const app = express();
const serveApp = (_request, response) =>
  response.type('html').set('Cache-Control', 'no-store').send(html);
app.get(['/', '/index.html'], serveApp);
app.use(express.static(browserDistFolder, { maxAge: '1y', index: false, redirect: false }));
app.get('/{*path}', serveApp);

const port = process.env.PORT || 4000;
const server = app.listen(port, (error) => {
  if (error) throw error;
  const address = server.address();
  console.log(
    `Web server listening on http://localhost:${typeof address === 'object' && address ? address.port : port}`,
  );
  console.log(`Backend API: ${apiBaseUrl}`);
});
