# Task API

The Task API runs a subagent headlessly with the normal delegate tools available outside chat. It starts in a fresh workspace with no existing project files or session history. Results are returned over HTTP.

Synthetic workspaces and conversations use `api-task-<run_id>` so they are easy to find in logs. Configured shared skills and knowledge remain available. The workspace is separate from existing chat projects.

## Configure API keys

Generate a random key and its lowercase SHA-256 hash:

```bash
TASK_API_KEY=$(openssl rand -hex 32)
TASK_API_KEY_SHA256=$(printf '%s' "$TASK_API_KEY" | openssl dgst -sha256 -r | cut -d ' ' -f 1)
```

Store the raw key in the client's secret manager. Set `TASK_API_KEY_SHA256` in Sidekick's deployment environment and add this to `application.yml`:

```yaml
task-api:
  enabled: true
  auth:
    clients:
      ci-reporter:
        key-hashes:
          - "${TASK_API_KEY_SHA256}"
```

The Task API is disabled unless `task-api.enabled` is `true`. When disabled, its routes are not registered.

Use HTTPS. Send the raw key as `Authorization: Bearer <key>`. Web UI access tokens are not accepted. With no configured hashes, no client can authenticate.

To rotate a key, add the new hash under the same client ID and redeploy, switch the client, then remove the old hash and redeploy again. Run ownership follows the client ID.

## Submit and poll

```bash
curl -X POST "$SIDEKICK_URL/api/internal/tasks" \
  -H "Authorization: Bearer $TASK_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Summarize what a build cache does."}'
```

`prompt` is required, nonblank, and limited to 20,000 characters. `subagent_type` is optional and defaults to `general`. It accepts any type in the `Task` catalog, including extensions.

Submission returns `202 Accepted`, a JSON `run_id`, and a `Location` header with the polling path.

```bash
curl "$SIDEKICK_URL/api/internal/tasks/$RUN_ID" \
  -H "Authorization: Bearer $TASK_API_KEY"
```

Polling returns `200 OK` with `status`: `queued`, `running`, `completed`, or `failed`. A completed run includes `result`; a failed run includes `error`. Missing or invalid keys get `401`. Unknown, expired, and other clients' run IDs get `404`.

## Structured results

Add `output_schema` to the submission to get an object instead of a string in `result`:

```json
{
  "prompt": "Summarize what a build cache does.",
  "output_schema": {
    "type": "object",
    "properties": { "summary": { "type": "string" } },
    "required": ["summary"],
    "additionalProperties": false
  }
}
```

The root must be an object. Objects must list all properties as required and set `additionalProperties` to `false`. Supported types are `object`, `array` (with one `items` schema), `string`, `integer`, `number`, `boolean`, and `null`. Optional `description` is supported; other keywords are rejected. The schema is limited to 16,384 characters and 12 levels of nesting below the root. Unsupported schemas return `400`.

The configured normal model and OpenRouter provider must support native JSON Schema output with tools. Sidekick sends the schema during the existing tool loop and requires OpenRouter to honor the request parameters. An answer that does not match the schema fails with `error_code: "invalid_output"`. Omit `output_schema` to keep the text result.

## Run limits

Run status and results are held in memory on a single instance and lost on restart. Poll the same instance that accepted the task. Finished results are available for one hour. Workspace files are kept separately and do not expire with the result. Up to four tasks execute at once, with a ten-minute execution timeout. Submissions return `429` when the instance holds 256 queued, running, or unexpired finished results.
