# Web authentication

Spring Security OAuth2 Client handles Google sign-in. Admission requires a verified
email and an exact configured email domain. No user provisioning or roles exist.

## Authentication contract

- Google credentials stay on the backend. A short-lived, single-use PKCE handoff
  delivers the Sidekick access token to the initiating browser.
- The frontend treats access tokens as opaque. Only authenticated
  `GET /api/web/user/profile` establishes the displayed account identity.
- `/api/web/user/**` and `/api/web/admin/**` require a validated Sidekick bearer
  token. Servlet sessions and cookies do not authenticate these APIs.
- Public Sidekick branding is separate at `/api/web/sidekick/profile`.
- A user API 401 clears frontend identity and displays login, without silently
  retrying that failed request using the browser cookie.

## Cookie and JWT lifetime invariant

The Sidekick JWT and `sidekick-encore` cookie share the same fixed expiry,
24 hours after Google sign-in. `WEB_AUTH_LIFETIME` in the HTTP auth adapter is
the single lifetime definition. The browser session stores the issued token;
cookie Max-Age uses its remaining lifetime. Restoring a session returns that
same token, never a new 24-hour deadline. Neither activity nor reload extends
authentication. Do not add independently configurable JWT and cookie lifetimes.

`sidekick-encore` is an opaque, host-only, HttpOnly credential scoped to
`Path=/api/web/auth/session`. Only this endpoint accepts it:

- `GET` checks the remembered browser and restores the access token, or returns 401.
- `DELETE` revokes the stored browser session and expires the cookie at the same path.

Both require `X-Sidekick-Session: 1`; credentialed CORS is restricted to the configured
frontend origin. The token exchange permits credentialed CORS to set the cookie,
but its path prevents the cookie from being sent back to the exchange endpoint.
HTTPS cookies use Secure and SameSite=None to support separate frontend/API sites.
Local HTTP uses SameSite=Lax without Secure. Production requires HTTPS and correct
forwarded-header configuration behind a trusted TLS proxy. Third-party-cookie
blocking requires same-site frontend/API hosting.

Access tokens remain in browser memory. After a reload, the browser restores the
token before requesting the authenticated profile. Sign-out revokes the remembered
browser and clears local identity; an already-issued bearer token remains valid
until its original deadline.

The remembered browser store is in memory, like the OAuth handoff store. A backend
restart requires Google sign-in again. Multiple instances require sticky routing
for browser authentication. No persistent user/session database was introduced.
