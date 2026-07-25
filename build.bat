@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Gradle。请先安装 Gradle 9.2.0，并将 gradle 加入 PATH。
  echo 也可以把项目上传到 GitHub，使用自带的 Actions 工作流构建。
  pause
  exit /b 1
)
gradle clean build --stacktrace
if errorlevel 1 (
  echo [错误] 构建失败，请查看上方日志。
  pause
  exit /b 1
)
echo [完成] JAR 位于 build\libs\
pause
