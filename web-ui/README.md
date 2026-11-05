# Web UI

Angular 22 application with routing, request-time server-side rendering (SSR), and client hydration.

## Setup

Node.js and pnpm versions are pinned in the repository's `mise.toml`. From the repository root:

```bash
mise install
cd web-ui
mise exec -- pnpm install --frozen-lockfile
```

## Development

From `web-ui/`:

```bash
mise exec -- pnpm start
```

Open <http://localhost:4200/>. The development server reloads when source files change.

## Tests

```bash
mise exec -- pnpm test --watch=false
```

## Production SSR

```bash
mise exec -- pnpm build
API_BASE_URL=https://api.example.com mise exec -- pnpm run serve:ssr:web-ui
```

Open <http://localhost:4000/>. Set `PORT` to change the port. Build output is in `dist/web-ui/`.

All routes use `RenderMode.Server` in `src/app/app.routes.server.ts`.
Deploy the same build to each environment; no source edits or rebuilds are needed to change these settings:

| Environment variable | Default                | Purpose                                                                            |
| -------------------- | ---------------------- | ---------------------------------------------------------------------------------- |
| `API_BASE_URL`       | Required in production | Absolute HTTP(S) backend URL reachable by both the SSR server and users' browsers. |
| `PORT`               | `4000`                 | SSR server port.                                                                   |

Set these on the running Node process, not on the build command. Restart the process after changing them.
The production server refuses to start if `API_BASE_URL` is missing, blank, or not an absolute HTTP(S) URL.
It logs the configured backend URL at startup. Building does not require this variable or a running backend.
Serve the frontend and backend over HTTPS in production. SSR accepts any hostname;
host restrictions belong at the deployment's ingress or reverse proxy.

### Backend profile API

`GET /api/web/sidekick/profile` supplies the configured `agent.name` and Slack identity
(display name, user ID, and workspace ID). Slack identity comes from the existing Slack
connection's `auth.test` and `users.info` results. The visible handle preserves the
Slack profile display name, including punctuation. No credentials are returned. All visible branding
and page titles use the API name; there is no frontend name setting or fallback name.

Set `API_BASE_URL` on the SSR server. `src/environments/environment.ts` only supplies
the default for `pnpm start` (development SSR). SSR transfers the configured URL to the browser through Angular
`TransferState`. Both call that backend directly; no API proxy is used. Profile data is transferred
into the HTML for hydration, avoiding a duplicate browser request.
The browser requires transferred configuration. If it is missing, startup shows a configuration error
without making backend requests. It never falls back to localhost. Serve application pages through the
SSR server, not directly from `dist/web-ui/browser/`.

The backend allows the UI origin `http://localhost:4200` for `/api/web/**` by default.
Set the Spring property `webui.allowed-origin` to the deployed UI origin.
For example, set `WEBUI_ALLOWED_ORIGIN=https://app.example.com` on the backend.
This remains separate from the frontend's runtime settings.

## Lint and format

```bash
mise exec -- pnpm lint
mise exec -- pnpm lint:fix
mise exec -- pnpm format
mise exec -- pnpm format:check
```

Oxlint checks TypeScript source files. Oxfmt formats the app, including Angular HTML templates.

## Interface scaffold

- `/app/overview`: Sidekick profile and shared analytics with a working 7/30-day filter.
- `/admin`: placeholder for configuration and operational tools. This is not an authorization boundary yet.
- `/` redirects to `/login`.

The overview is a lightweight profile with a Slack-style conversation and three live activity totals.
The sidebar’s **Kill switch** button opens a dialog for deleting one bot-authored Slack message
by permalink. The authenticated API uses the existing bot token and `chat:write`
scope; it cannot delete other authors' messages. Deletion does not remove stored
history or copies, stop the bot, or revoke exposed credentials.
`GET /api/web/user/stats?days=7` (or `days=30`) returns installation-wide usage from the
same `WeeklyStatsService` used by `stats__weekly`. The seven-day period is the default;
other periods return HTTP 400.

The totals cover sessions started in the period, with the same start-inclusive,
end-exclusive window as the tool. Teammates are distinct recorded participants across
those sessions. The third total shows consumed input and output tokens, labeled
“burned tokens”. The API also returns the channel count. Loading failures show a retry
action rather than sample values. Dedicated API prefixes
are `/api/web/user/**` (including shared analytics) and `/api/web/admin/**`.
API implementations live in the backend's `adapters/http/webui` package.
Both API prefixes require a valid Sidekick bearer token. Cookie sessions do not authenticate them.

## Google sign-in

Open `/login`. Spring Security's `oauth2Login()` handles
Google's authorization-code flow and OIDC verification. A temporary HTTP session holds
the login handshake only; it is invalidated on success or failure and never authenticates
user API requests. No user database, roles, refresh tokens, or persistent bearer-token storage are introduced.

After the Google callback, the browser receives a one-use, 60-second handoff code.
It exchanges that code and its PKCE verifier for a Sidekick-signed JWT. The verifier
and client state are held in tab-scoped `sessionStorage` during the redirect, then removed.
The JWT never appears in a redirect URL. Google credentials are discarded with the temporary session.
The token expires 24 hours after Google sign-in and is held in browser memory.
It is sent as a bearer token on subsequent user API requests. An HttpOnly
`sidekick-encore` cookie restores the same token after a reload, without extending
its expiry. The cookie's path is `/api/web/auth/session`; user APIs never accept it.
Signing out revokes the browser session and clears both the cookie and in-memory token.
Signing out does not revoke an already-issued JWT; it expires at its original deadline.

The frontend treats the access token as opaque. After exchange it calls
`GET /api/web/user/profile` with the bearer token to obtain the signed-in email.
That email appears in the bottom-left account card. A user API `401` clears
authentication and returns to `/login`. Public Sidekick metadata remains separate
at `/api/web/sidekick/profile`.

Configure a **Web application** OAuth client in Google Cloud. Add the backend callback
to **Authorized redirect URIs**: `http://localhost:7070/login/oauth2/code/google` for
development, or `https://your-backend/login/oauth2/code/google` in production.
Use an **Internal** OAuth audience for Workspace-only sign-in. The client secret stays on the backend.

Set these environment variables on the backend process:

```text
WEBUI_AUTH_GOOGLECLIENTID=your-client-id.apps.googleusercontent.com
WEBUI_AUTH_GOOGLECLIENTSECRET=<Google OAuth client secret>
WEBUI_AUTH_ALLOWEDEMAILDOMAIN=example.com
WEBUI_AUTH_JWTSIGNINGKEY=<Base64-encoded signing key>
```

Spring Boot binds these directly: replace property dots with underscores, remove
hyphens, and uppercase. No placeholder aliases are needed.

Generate the signing key with `openssl rand -base64 32`. Keep it out of source control
and retain it across restarts. A configured key must decode to at least 32 bytes.
Do not send it to the frontend. Keep `WEBUI_ALLOWED_ORIGIN` set to the frontend origin.
That origin also determines the callback destination (`/login`); clients cannot supply
an arbitrary return URL. Behind a trusted HTTPS reverse proxy, configure Spring's
forwarded-header handling so the Google redirect URI uses the public backend URL.

The domain comparison is exact and case-insensitive, with an optional leading `@`
in configuration. Subdomains do not match. Missing or blank domain configuration
rejects every login. Every accepted identity must have a nonblank, verified email.

Endpoints:

- `GET /api/web/auth/config` returns `enabled` only.
- `GET /oauth2/authorization/google?sidekick_code_challenge=...&sidekick_state=...`
  initiates Google sign-in; Spring handles `/login/oauth2/code/google`.
- `POST /api/web/auth/token` accepts JSON `{ "code": "<handoff>", "code_verifier": "<verifier>" }`.
  A successful response contains `access_token`, `token_type: "Bearer"`, `expires_at`
  (UTC ISO timestamp) only. Invalid, expired, or reused handoffs return 401.
  Domain rejection returns the browser to `/login?error=domain`. Disabled domain
  configuration rejects token exchange with 403; missing client/signing configuration returns 503.
- `GET /api/web/user/profile` returns `{ "email": "person@example.com" }` from
  the validated Sidekick token, or 401 without a valid bearer token. This response
  is not transferred through SSR hydration.
- `GET /api/web/auth/session` accepts the `sidekick-encore` cookie and returns the
  existing access token, or 401. `DELETE` on the same path revokes the session and
  expires the cookie. Both require `X-Sidekick-Session: 1` and credentialed browser
  requests. CORS allows only the configured frontend origin.

The cookie and JWT share one fixed 24-hour deadline. There is no sliding expiry
or independent lifetime setting. The cookie is host-only and HttpOnly, with
`Secure; SameSite=None` on HTTPS (including cross-site frontend/API deployments).
Local HTTP development uses `SameSite=Lax` without `Secure`. Production requires
HTTPS; configure forwarded headers behind a trusted TLS proxy so Spring sees HTTPS.
Browsers that block third-party cookies require same-site frontend/API hosting.

Configuration and token responses use `Cache-Control: no-store`. Spring verifies Google
OIDC credentials; the success handler requires a subject and verified email before issuing a token. Sidekick JWTs
use HS256, a provider-qualified subject, and an `email` claim. Defaults are issuer
`sidekick`, audience `sidekick-api`, and a fixed 24-hour lifetime. Override issuer
and audience with `webui.auth.jwt-issuer` and `webui.auth.jwt-audience`.
The temporary handshake, handoff, and remembered browser stores currently belong
to one backend instance. Backend restarts clear remembered browsers; multi-replica
deployments need sticky routing for browser authentication.

## API boundary

`src/app/api/api.service.ts` is the only HTTP client. Components and state services
use its endpoint methods. All responses are requested as `unknown`, validated with
strict Zod schemas in `api.schemas.ts`, and then returned as schema-inferred types.
Validation failures use the same error/retry UI as transport failures.

Oxlint restricts direct `HttpClient` and `httpResource` imports outside this service.
TypeScript strict checking, exact optional properties, unchecked-index checking, and
Angular strict templates are enabled. See `AGENTS.md` before adding endpoints.
