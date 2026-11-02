# AGENTS.md — OhMyLoaderGradle

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. It defines the build order, tests, style, commit and
pull-request rules, releases, security, and licensing. This file adds only agent-specific constraints.

- Work only in this repository. Consume the loader through Maven coordinates; do not add sibling source
  paths, `includeBuild`, shared catalogs, or source mounts.
- Publish the four loader coordinates used by CI (`oml-devtools`, `oml-core`, `oml-api`, `oml-content`)
  to mavenLocal before building. Align `oml_version` in both repositories when the version changes.
- Run `./gradlew build` and `./gradlew test` for every plugin change.
- Do not add a compile dependency on `oml-native`; the plugin resolves it at runtime and falls back to the
  vanilla codec when it is absent.
- Preserve the plugin marker, publication coordinates, and task names; they are the consumer contract.
- Keep task actions configuration-cache compatible; do not capture `Project` or other non-serializable
  objects.
- Do not commit `build/`, `.gradle/`, local paths, or published artifacts.
- Commit in this repository. Do not push, tag, or publish unless explicitly asked. Release order is:
  loader tag first, then the plugin tag.
