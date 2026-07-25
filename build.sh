#!/usr/bin/env sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  echo "未找到 Gradle。请安装 Gradle 9.2.0，或使用项目自带的 GitHub Actions 工作流。" >&2
  exit 1
fi
gradle clean build --stacktrace
echo "构建完成：build/libs/"
