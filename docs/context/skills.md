# Skills

Sidekick discovers skills from remote Git repositories listed in `${agent.working-directory}/skills.json`.

## Source Model

Remote Git repositories are the only supported skill source. Sidekick does not read project-local `.agents/skills`, user-local skill directories, or bundled application resources.

Configuration shape:

```json
{
  "skills": [
    {
      "url": "git@github.com:deil/skills",
      "path": "skills",
      "sshKeyPath": "/home/sidekick/.ssh/skills"
    }
  ]
}
```

`url` is the Git repository URL. `path` is the repository-relative directory containing skill folders. `sshKeyPath` is optional and points to a private key file used for Git SSH operations for that repository. Missing `skills.json` and empty `skills` lists are no-ops.

`path` is optional. If omitted or blank, Sidekick scans the repository root for skill folders.

`skills.json` is read from the agent working directory, not the repository root or state directory.

## Checkout Storage

Configured repositories are cloned or refreshed under the agent working directory:

```text
${agent.working-directory}/skills/
```

Each repository gets a stable unique checkout folder so repositories with the same final path segment do not collide. The checkout location is working data, not session state.

## Discovery

For each repository, Sidekick scans:

```text
<checkout>/<path>/*/SKILL.md
```

When `path` is omitted or blank, Sidekick scans:

```text
<checkout>/*/SKILL.md
```

Only immediate child directories under `<checkout>/<path>` are considered skill folders. Nested skill folders are not discovered.

`folder` on the discovered `Skill` is the base skill directory and excludes `SKILL.md`.

Bad skill folders are skipped. A missing configured `<path>` yields no skills for that repository.

## Skill Metadata

`SKILL.md` must start with YAML frontmatter. Supported fields:

```yaml
---
name: example-skill
description: Short description used for discovery.
disable-model-invocation: false
user-invocable: true
---
```

`name` and `description` are required. Descriptions are truncated to 1536 characters during discovery.

Optional fields:

- `disable-model-invocation`, defaults to `false`
- `user-invocable`, defaults to `false`

`name` must use lowercase letters, numbers, and single hyphens. It must match the parent folder name.

Discovered skills retain the parsed metadata and the skill base folder. The base folder excludes `SKILL.md`.

## Turn Prompt Catalog

`TurnPromptBuilder` includes a skills catalog in the per-turn user prompt when the discovered catalog contains model-invocable or user-invocable skills.

Model-invocable skills are skills where `disableModelInvocation == false`. Skills with `disable-model-invocation: true` are excluded from the model-facing catalog.

User-invocable skills are skills where `userInvocable == true`. They are listed separately so the model knows which skill names users may explicitly request, including skills where `disable-model-invocation: true`.

The catalog follows Agent Skills progressive disclosure: the turn prompt includes only the skill name, description, and virtual location of `SKILL.md`. Full skill instructions are not embedded in the base system prompt or turn prompt.

`/data/skills` locations resolve through `parseVirtualPath()` against `AgentConfig.skillsDirectoryPath()`.

The skills catalog is thread-context bootstrap data, not static system prompt state. It is rendered by `buildThreadContext()` only when the turn is bootstrapping model-visible session context; follow-up turns with existing Koog history do not repeat it.

The section is wrapped in `<skills>`. It instructs the model to use `activateSkill` with the skill name when a task matches an `<available_skills>` entry. `<user_invocable_skills>` is not permission for autonomous model activation; the model may use those skills only when the current user explicitly invokes one.

Structural shape:

```xml
<skills>
  ...
  <available_skills>
    <skill>
      <name>example-skill</name>
      <description>Short discovery description.</description>
      <location>/data/skills/repo/example-skill/SKILL.md</location>
    </skill>
  </available_skills>
  <user_invocable_skills>
    <skill>
      <name>user-only-skill</name>
      <description>Short discovery description.</description>
      <location>/data/skills/repo/user-only-skill/SKILL.md</location>
    </skill>
  </user_invocable_skills>
</skills>
```

Model-invocable skills are listed under `<available_skills>`. User-invocable skills are listed under `<user_invocable_skills>`. Each `<skill>` entry contains:

- `<name>`
- `<description>`
- `<location>` pointing to the `/data/skills` virtual path for `SKILL.md`

If no model-invocable or user-invocable skills exist, the `<skills>` section is omitted.

## User Invocation

User-invocable skills are explicit Slack syntax, not model intent inference. Sidekick detects deterministic positive invocation patterns during message preprocessing, before the message is recorded for the turn. A valid detection is materialized on `SessionMessage.explicitSkillInvocation`. Prompt builders must not perform invocation detection.

Supported MVP forms:

- Slash-style `/skill-name` anywhere in the Slack message.
- Natural language `activate skill-name`, `use skill-name`, `invoke skill-name`, `run skill-name`, or `call skill-name`.
- Natural-language forms may include optional `skill` immediately before or after the skill name, and optional `the` immediately before the skill name: `use skill-name`, `use skill skill-name`, `use the skill-name`, `use skill-name skill`, or `use the skill-name skill`.

Sidekick resolves the detected name against the discovered catalog and accepts only skills where `userInvocable == true`. Unknown skill names and skills with `user-invocable: false` are ignored and the message continues as a normal turn.

Only one skill is user-invoked per message in the MVP. Slash invocation wins over natural-language invocation. Within the selected invocation type, Sidekick chooses one deterministic first match.

Slash-style invocation wins. If Sidekick detects `/skill-name` and the skill is user-invocable, it accepts that invocation without applying natural-language or negative-request checks.

For natural-language invocation, Sidekick blocks the same activation pattern when it is prefixed by a negative instruction: `do not`, `don't`, `dont`, or `never`. Examples: `don't use skill-name`, `do not activate skill-name`, or `never run skill-name`.

When `SessionMessage.explicitSkillInvocation` is present, Sidekick does not call `activateSkill` directly. It adds an `<explicit_skill_invocation>` section before `<current_instruction>`. The section body starts with an instruction hint, then a blank line, then the slash-prefixed skill name:

```xml
<explicit_skill_invocation>
The user explicitly requested this skill. Call activateSkill with this name before answering.

/example-skill
</explicit_skill_invocation>
```

## Skill Activation

`activateSkill` loads a discovered skill by name from the in-memory catalog. It reads the skill's `SKILL.md`, strips frontmatter, and returns the instruction body wrapped in `<skill_content name="...">`.

The wrapped response includes the skill directory as a `/data/skills` virtual path and lists bundled resource files without reading them. Relative paths in activated skill instructions are relative to that skill directory.

## Technical Decisions

Checkout folder names are deterministic and collision-resistant. Sidekick derives a readable prefix from the repository URL's final path segment, strips a `.git` suffix, replaces unsafe path characters with `_`, and appends the first 12 hex characters of `sha256(url)`.

Examples:

```text
git@github.com:deil/skills.git -> skills-<12-char-url-hash>
git@github.com:other/skills.git -> skills-<different-12-char-url-hash>
```

The hash is based on the full URL, not just the repository name, so two repositories with the same final path segment still get different checkout folders.

The checkout directory is `AgentConfig.skillsDirectoryPath()`, derived from `agent.working-directory`; persisted session state remains under `agent.state-directory`.

Repository clone and refresh use the shared JGit adapter, not the `git` CLI. Repository refresh resets the checkout to `origin/HEAD`.

## Key Files

- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/Skills.kt` – skills config loading, skill discovery, and in-memory catalog ownership.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/git/GitRepositories.kt` – shared JGit checkout path, clone, fetch, reset, and SSH-key wiring.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/SkillCatalogReloading.kt` – implements the skill catalog reload port by re-reading config, refreshing repositories, and rebuilding the catalog.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/Utils.kt` – small generic helpers used by skills discovery.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/UserSkillInvocation.kt` – detects user skill invocation before LLM prompting.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/ports/Skills.kt` – defines the skill catalog reload port used by tools.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/TurnPromptBuilder.kt` – renders the skills catalog and already-materialized explicit skill invocation in the turn prompt.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/skills/SkillTools.kt` – exposes skill tools including `activateSkill` and `reloadSkills`.
