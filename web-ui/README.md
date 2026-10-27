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

`GET /api/web/user/profile` supplies the configured `agent.name` and Slack identity
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
- `/` redirects to the overview.

The overview is a lightweight profile with a Slack-style conversation and three live activity totals.
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
Authentication and authorization are not implemented by this scaffold.

## API boundary

`src/app/api/api.service.ts` is the only HTTP client. Components and state services
use its endpoint methods. All responses are requested as `unknown`, validated with
strict Zod schemas in `api.schemas.ts`, and then returned as schema-inferred types.
Validation failures use the same error/retry UI as transport failures.

Oxlint restricts direct `HttpClient` and `httpResource` imports outside this service.
TypeScript strict checking, exact optional properties, unchecked-index checking, and
Angular strict templates are enabled. See `AGENTS.md` before adding endpoints.
