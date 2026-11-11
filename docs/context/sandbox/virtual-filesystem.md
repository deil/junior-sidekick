# Virtual Filesystem

Sidekick exposes local files through stable virtual roots.

```text
/
├── data/
│   ├── session/
│   │   └── <slack-file-id>-<filename>
│   ├── skills/
│   │   └── <repository-checkout>/
│   │       └── <skill-directory>/
│   │           ├── SKILL.md
│   │           └── <skill-resources>
│   ├── global/
│   │   └── <repository-checkout>/
│   │       └── <configured-path>/
│   │           └── <global-context-files>
│   └── project/ (read-write)
│       └── <channel-scoped-project-files>
└── work/ (read-write)
    └── <bash-files>
```
