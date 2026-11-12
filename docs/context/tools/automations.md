# Automations

Automations are project-owned instructions executed by a trigger. Scheduled triggers are implemented; other trigger objects can be stored but are not dispatched.

## Model

An `Automation` contains:

- `id`: globally unique SQLite-generated integer primary key.
- `projectUid`: owning project; currently the Chat channel identifier.
- `uid`: stable project-local management number, starting at 1 and never reused.
- Required `name`, optional `description`, and required `instructions`.
- `trigger`: a freeform JSON object with a required string `type`.
- `enabled`, defaulting to `true`.
- Optional `lastRunAt`, recording when a run was claimed, not when it completed.
- Required `createdAt` and `updatedAt` UTC timestamps.

An `AutomationRun` carries one claimed automation and its owning project. Each run starts a fresh Session; automation identity creates no continuity between runs.

Names are unique within a project, including paused automations. The application stores a lowercase `name_key` to enforce the same uniqueness in SQLite. A project may have at most five enabled automations, regardless of trigger type.

## Triggers

Scheduled triggers have this shape:

```json
{
  "type": "scheduled",
  "timezone": "Europe/London",
  "schedule": "0 9 * * 1-5"
}
```

The schedule uses five-field cron (`minute hour day-of-month month day-of-week`) and a separate IANA timezone. Seconds are not accepted. The scheduler supplies zero seconds to Spring's cron parser. The application normalizes scheduled fields and preserves additional trigger fields.

Cron is evaluated in the stored timezone, without conversion to a persisted UTC schedule. Nonexistent local times during daylight-saving transitions are skipped. Repeated local times receive normal scheduler behavior, subject to the cooldown.

Unknown trigger types remain persisted but are ignored by the scheduled dispatcher. Updating a trigger replaces the entire object.

## Management

Users manage automations through conversation in the owning project. Mutations require an explicit request directed at Sidekick; this is a conversational rule, not a tool-level authorization check. Current channel invocation rules apply; there is no creator-specific ownership or cross-project management.

The orchestrator exposes these tools on Slack-backed turns, including automation turns:

- `automations__create`: `name`, optional `description`, `instructions`, `trigger`, optional `enabled`.
- `automations__list`: all enabled and paused automations, with active count and limit.
- `automations__update`: project-local `uid` plus changed fields. An empty description clears it; `enabled` pauses or resumes.
- `automations__delete`: permanently deletes the automation identified by project-local `uid`.

Global `id`, local `uid`, timestamps, and `last_run_at` cannot be changed by users. There are no separate manual-run, get, pause, or resume tools. Subagents do not receive automation-management tools.

## Persistence and legacy import

**SQLite is the only write destination.** Spring JDBC repositories own SQL and row mapping; repository ports live in `ports/`, and models and services in `application/core/`.

`automations` stores the model, with JSON `trigger`, a global `id` primary key, and unique `(project_uid, uid)` and `(project_uid, name_key)` pairs.

`automation_projects` stores each project's last allocated local UID. Its presence also marks SQLite as authoritative, including when the project's automation list is empty. Deleting all automations does not delete this row or reset the sequence.

**`AutomationImporter.importProject(projectUid)` is the shared, idempotent import routine.** It runs inside a project transaction before management reads, mutations, and scheduled due-run evaluation. If the project marker already exists, it does not read legacy files.

Otherwise it loads the legacy snapshot from:

```text
<agent.state-directory>/slack/channels/<project-uid>/jobs.jsonl
<agent.state-directory>/slack/channels/<project-uid>/jobs-sequence
```

The importer preserves all records and the allocation high-water mark. Legacy `id` becomes `uid`; SQLite allocates a global `id`. Legacy `prompt` becomes `instructions`, and `schedule`/`timezone` become a `scheduled` trigger. `enabled` and `last_run_at` survive unchanged. Both creation and update timestamps are set to the import time.

The marker, sequence, and imported rows commit together. Failure leaves the project eligible for another import attempt. Legacy files are read-only and remain untouched. An empty source still creates an authoritative SQLite project marker.

The scheduler discovers projects from both SQLite and legacy directories, including legacy sequence-only directories. It imports each project before checking which scheduled automations are due, even if no automation is due in that scan. Listing also imports, so callers always receive persisted global IDs and timestamps.

## Scheduled execution

The scheduler checks the current minute once per minute; missed occurrences are not replayed. Loading during a matching minute can make an automation eligible in that minute.

A scheduled automation is eligible when enabled, its cron matches the current minute, its previous claim predates that occurrence, and its cooldown has elapsed. Cooldown uses minute-truncated timestamps and is fixed at one hour. Long runs may overlap once the cooldown permits another run.

Claims persist `last_run_at` and `updated_at` before dispatch. Project-local locking and SQLite transactions serialize claims and management operations within the application. This is not a multi-instance coordination protocol.

**Claimed occurrences are consumed even if dispatch or execution fails.** There are no automatic retries or durable execution queue; a process crash after claiming can lose that occurrence.

Each firing creates a unique session (`automation_<uid>_<claim-timestamp>` within the project) and executes a first-class automated Turn trigger. It bypasses inbound-message admission and participation classification, reusing normal agent execution, tool registration, persistence, and failure handling.

Runs receive normal system instructions, configured model behavior, project context, and tools including skills, subagents, MCP, and bash when configured. They do not inherit earlier runs' messages, compactions, settings, attachments, or work files.

Automated turns are headless: `Todo` updates stay in memory without publishing progress lists. Final answers and normal Turn failures post as root channel messages through the Chat adapter.

There is no dedicated run-history store, job-specific timeout, or run-status API. Normal Turn execution limits and Session state apply. Scheduled execution does not pass through incoming-message channel metadata refresh.

## Runtime flow

```text
conversation -> AutomationTools -> AutomationService
             -> AutomationImporter -> AutomationsRepository

minute tick -> AutomationService.claimDueRuns
            -> AutomationImporter -> persisted claim
            -> RunAutomationUsecase -> fresh Session -> agent Turn -> root reply
```

`core` owns models, services, import, ports, and SQLite/legacy-file adapters. `tools` owns management tools and registration. `interfaces` owns the Spring scheduler, run use case, and Slack dispatch/delivery.
