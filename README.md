# OhMyLoader Gradle Plugin

`org.ohmyloader.gradle` is the official Gradle plugin for
[OhMyLoader](https://github.com/OhMyLoader/OhMyLoader).

## Build

The plugin compiles against the loader-published `oml-devtools` (the fetch tasks call its downloader in
process) and `oml-core` (`omlJar` scans the output with its `ModScanner` under the same `@Mod` contract).
ASM (`org.ow2.asm:asm` and `asm-tree`) comes from Maven Central. Publish the loader coordinates first, then
build the plugin:

```bash
# In the OhMyLoader repository:
./gradlew :oml-devtools:publishMavenPublicationToMavenLocal :oml-core:publishMavenPublicationToMavenLocal
# Back in this repository:
./gradlew build
```

`oml-native` is not a build dependency. At runtime the plugin resolves the published `oml-native` artifact by
coordinate; when it is absent, the loader falls back to the vanilla compression implementation.
