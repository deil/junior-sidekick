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
      "path": "skills"
    }
  ]
}
```

`url` is the Git repository URL. `path` is the repository-relative directory containing skill folders. Missing `skills.json` and empty `skills` lists are no-ops.

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
- `user-invocable`, defaults to `true`

`name` must use lowercase letters, numbers, and single hyphens. It must match the parent folder name.

Discovered skills retain the parsed metadata and the skill base folder. The base folder excludes `SKILL.md`.

## Turn Prompt Catalog

`TurnPromptBuilder` includes a skills catalog in the per-turn user prompt when the discovered catalog contains model-invocable skills.

Model-invocable skills are skills where `disableModelInvocation == false`. Skills with `disable-model-invocation: true` are excluded from the model-facing catalog.

The catalog follows Agent Skills progressive disclosure: the turn prompt includes only the skill name, description, and virtual location of `SKILL.md`. Full skill instructions are not embedded in the base system prompt or turn prompt.

`skills:/` locations resolve through `parseVirtualPath()` against `AgentConfig.skillsDirectoryPath()`.

The skills catalog is thread-context bootstrap data, not static system prompt state. It is rendered by `buildThreadContext()` only when the turn is bootstrapping model-visible session context; follow-up turns with existing Koog history do not repeat it.

The section is wrapped in `<skills>`. It instructs the model to use `activateSkill` with the skill name when a task matches a listed skill description.

Structural shape:

```xml
<skills>
  ...
  <available_skills>
    <skill>
      <name>example-skill</name>
      <description>Short discovery description.</description>
      <location>skills:/repo/example-skill/SKILL.md</location>
    </skill>
  </available_skills>
</skills>
```

Available skills are listed under `<available_skills>`. Each `<skill>` entry contains:

- `<name>`
- `<description>`
- `<location>` pointing to the `skills:/` virtual path for `SKILL.md`

If no model-invocable skills exist, the `<skills>` section is omitted.

## Skill Activation

`activateSkill` loads a discovered skill by name from the in-memory catalog. It reads the skill's `SKILL.md`, strips frontmatter, and returns the instruction body wrapped in `<skill_content name="...">`.

The wrapped response includes the skill directory as a `skills:/` virtual path and lists bundled resource files without reading them. Relative paths in activated skill instructions are relative to that skill directory.

## Technical Decisions

Checkout folder names are deterministic and collision-resistant. Sidekick derives a readable prefix from the repository URL's final path segment, strips a `.git` suffix, replaces unsafe path characters with `_`, and appends the first 12 hex characters of `sha256(url)`.

Examples:

```text
git@github.com:deil/skills.git -> skills-<12-char-url-hash>
git@github.com:other/skills.git -> skills-<different-12-char-url-hash>
```

The hash is based on the full URL, not just the repository name, so two repositories with the same final path segment still get different checkout folders.

The checkout directory is `AgentConfig.skillsDirectoryPath()`, derived from `agent.working-directory`; persisted session state remains under `agent.state-directory`.

Repository refresh uses the default remote branch when it can be resolved and falls back to `origin/main` otherwise.

## Key Files

- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/Skills.kt` – skills config loading, repository checkout/refresh, skill discovery, and in-memory catalog ownership.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/skills/Utils.kt` – small generic helpers used by skills discovery.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/TurnPromptBuilder.kt` – renders the model-facing skills catalog in the turn prompt.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/skills/ActivateSkillTools.kt` – loads full skill instructions through the `activateSkill` tool.
