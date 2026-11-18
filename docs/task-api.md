# Task API

The Task API runs a built-in subagent in a fresh context. It has no tools, project files, session history, or chat output. Results are returned over HTTP.

## Configure API keys

Generate a random key and its lowercase SHA-256 hash:

```bash
TASK_API_KEY=$(openssl rand -hex 32)
TASK_API_KEY_SHA256=$(printf '%s' "$TASK_API_KEY" | openssl dgst -sha256 -r | cut -d ' ' -f 1)
```

Store the raw key in the client's secret manager. Set `TASK_API_KEY_SHA256` in Sidekick's deployment environment and add this to `application.yml`:

```yaml
sidekick:
  task-api:
    clients:
      ci-reporter:
        key-hashes:
          - "${TASK_API_KEY_SHA256}"
```

Use HTTPS. Send the raw key as `Authorization: Bearer <key>`. Web UI access tokens are not accepted. With no configured hashes, no client can authenticate.

To rotate a key, add the new hash under the same client ID and redeploy, switch the client, then remove the old hash and redeploy again. Run ownership follows the client ID.

## Submit and poll

```bash
curl -X POST "$SIDEKICK_URL/api/internal/tasks" \
  -H "Authorization: Bearer $TASK_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Summarize what a build cache does."}'
```

`prompt` is required, nonblank, and limited to 10,000 characters. `subagent_type` is optional and defaults to `general`, the available built-in type.

Submission returns `202 Accepted`, a JSON `run_id`, and a `Location` header with the polling path.

```bash
curl "$SIDEKICK_URL/api/internal/tasks/$RUN_ID" \
  -H "Authorization: Bearer $TASK_API_KEY"
```

Polling returns `200 OK` with `status`: `queued`, `running`, `completed`, or `failed`. A completed run includes `result`; a failed run includes `error`. Missing or invalid keys get `401`. Unknown, expired, and other clients' run IDs get `404`.

Runs are held in memory on a single instance and lost on restart. Poll the same instance that accepted the task. Finished runs are available for one hour. Up to four tasks execute at once, with a two-minute execution timeout. Submissions return `429` when the instance holds 256 queued, running, or unexpired finished runs.
