# Contributing to the OhMyLoader Gradle Plugin

This repository contains `org.ohmyloader.gradle`, the official Gradle plugin for OhMyLoader mod projects.
It resolves the loader by Maven coordinate, configures the game dependencies, and provides the
`runClient` / `runServer` tasks. It does not contain loader source or the native library.

## Before you start

- Open an issue before changing the plugin's public DSL, task names, or dependency behavior.
- Keep this repository independent. Do not add relative paths to a loader checkout, a shared version
  catalog, `includeBuild`, or a source-directory mount. The loader is consumed by Maven coordinate only.
- The loader and plugin `gradle.properties` files must carry the same `oml_version`; align both files in
  the same change.

## Prerequisites

- JDK 27. CI uses the Zulu distribution.
- The Gradle wrapper in this repository (Gradle 9.9.1).
- A sibling `OhMyLoader` checkout for the loader publications.
- No Zig is required: the plugin does not build `oml-native`. At runtime it resolves the published
  artifact and falls back to the vanilla codec when the artifact is absent.

## Build and test

Publish the loader coordinates the plugin resolves, then build this repository:

```bash
(cd ../OhMyLoader && ./gradlew \
  :oml-devtools:publishMavenPublicationToMavenLocal \
  :oml-core:publishMavenPublicationToMavenLocal \
  :oml-api:publishMavenPublicationToMavenLocal \
  :oml-content:publishMavenPublicationToMavenLocal)
./gradlew build
./gradlew test
./gradlew publishToMavenLocal
```

`./gradlew build` runs the plugin tests. They apply the plugin to throwaway projects with Gradle's
`ProjectBuilder`; they do not launch Minecraft and do not need a display. `publishToMavenLocal` publishes
the plugin jar and plugin marker for the test mod and local consumers.

If the loader version in the sibling checkout does not match this repository's `oml_version`, the build
fails with a missing `org.ohmyloader` coordinate. Fix the version alignment before debugging the plugin.

## Code and documentation style

- Code comments and first-party documentation are American English in plain technical style. State the
  constraint, command, or fact; omit background and marketing.
- Keep the plugin a guest in the consumer's build: do not attach manifests to jars it does not own and do
  not add dependencies that the consumer did not ask for.
- Use typed Gradle APIs where they exist. Keep task configuration cache compatible; do not capture
  `Project` or other non-serializable objects in task actions.
- Add a regression test for every fixed behavior. Tests should cover the publication surface and the
  generated task inputs, not only a helper function.
- Do not introduce `TODO`, `FIXME`, or `@Deprecated`; the repository baseline is zero.

## Commit and pull request

- Branch from `main`; do not commit to the upstream `main` directly.
- Use Conventional Commits, for example `fix(plugin): ...` or `feat(plugin): ...`. One concern per commit.
  The body states the constraint and the evidence, not the investigation.
- Run the build and tests above and include the commands and results in the pull request.
- Include tests and documentation in the same change. Do not commit `build/`, `.gradle/`, local paths, or
  published artifacts.
- Push your branch to your fork and open a pull request. Do not push tags or release branches.

## Releases (maintainers)

- A `v*` tag publishes the plugin jar and plugin marker to GitHub Packages. The loader must carry the same
  tag first; the release job checks out the loader at that tag and builds the plugin against it.
- GitHub Packages requires authentication even for public-repo reads. Do not add repository credentials to
  the build; the release job supplies `GITHUB_TOKEN`.
- The plugin release is build and test only. The loader tag gate verifies a pinned plugin source, not the
  plugin release artifact; do not claim real-game coverage for a plugin release unless it was run.
- Do not tag a pull request or push a release tag without explicit maintainer approval.

## Security

Do not report a vulnerability in a public issue. Use GitHub's private vulnerability reporting for this
repository; include the affected version or commit, reproduction steps, and impact. If private reporting
is unavailable, contact the maintainer through GitHub before public disclosure.

## License

The project is licensed under AGPL-3.0. By submitting a contribution, you confirm that you have the right
to license it under AGPL-3.0. No CLA or `Signed-off-by` is required. Do not submit code with an
incompatible license.
