# Web UI practices

- `src/app/api/api.service.ts` is the only place allowed to inject `HttpClient` or perform HTTP calls. Add an explicit method there for each endpoint. Components and state services use these methods; do not use `httpResource`, `fetch`, or direct HTTP elsewhere.
- Every API response must be requested as `unknown` and validated with Zod inside `ApiService` before it reaches consumers. Define strict schemas in `src/app/api/api.schemas.ts` and infer response types with `z.infer`. Do not duplicate them as hand-written interfaces or assert unknown JSON into a response type.
- Keep TypeScript strict checking, unchecked-index checking, exact optional-property types, and Angular strict templates enabled. Do not use `any` or non-null assertions to bypass API validation.
- The backend base URL belongs in `src/environments/environment.ts`. Do not add an API proxy.
- All visible branding comes from the backend profile. Do not hardcode the project name as the agent name.
- Use pnpm through mise, oxlint for linting, and oxfmt for formatting. Do not add Prettier.
- No animations, transitions, or smooth scrolling. Restore the desktop viewport after mobile browser checks. Use Orca's browser for visual checks.
