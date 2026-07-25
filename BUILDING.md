# 构建说明

## GitHub Actions

1. 解压源码包。
2. 用解压目录中的内容覆盖原仓库内容，仓库根目录应直接看到：
   - `.github`
   - `src`
   - `build.gradle`
   - `gradle.properties`
   - `settings.gradle`
3. 提交并推送到 `main` 或 `master`。
4. 打开仓库的 `Actions → Build`。
5. 等待最新运行显示绿色对勾。
6. 打开该次运行的 `Summary`，在页面底部下载 Artifact：
   `skeleton-searcher-1.21.11`。
7. 解压后使用 `skeleton-searcher-1.5.1.jar`，不要使用 `-sources.jar`。

## 构建环境

- Java 21
- Gradle 9.2.0
- Fabric Loom 1.14.10
- Minecraft 1.21.11
