# OhMyLoader Gradle 插件

`org.ohmyloader.gradle` —— [OhMyLoader](https://github.com/OhMyLoader/OhMyLoader) 的官方 Gradle 插件。

## 构建

本插件的编译依赖是 loader 仓库发布的 `oml-devtools`（fetch 任务在进程内调用它的下载 API）与
`oml-core`（`omlJar` 用它的 `ModScanner` 按同一个 `@Mod` 契约扫描产物），另有第三方 ASM
（`org.ow2.asm:asm` / `asm-tree`，从 Maven Central 解析，无需自行发布），先发布再构建：

```bash
# 在 OhMyLoader 仓库执行：
./gradlew :oml-devtools:publishMavenPublicationToMavenLocal :oml-core:publishMavenPublicationToMavenLocal
# 回到本仓库：
./gradlew build
```

`oml-native` 不是本插件的依赖：运行环境的 natives 由插件按坐标解析已发布的 `oml-native` 制品，仓库里没有时降级为
vanilla 自带的压缩实现。
