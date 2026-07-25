# 第六版验证说明（1.5.1）

## 已完成的源码检查

- `fabric.mod.json` 已进行 JSON 解析检查。
- Java 包名、作者和许可证署名均为 `yym`，未发现真实姓名残留。
- 已检查不存在旧版不兼容调用：
  - `RenderSystem.disableDepthTest()`
  - `RenderSystem.enableDepthTest()`
  - `context.consumers().draw(...)`
- 隔墙管线明确配置：
  - `DepthTestFunction.NO_DEPTH_TEST`
  - `withDepthWrite(false)`
  - `withCull(false)`
- 普通管线继续使用正常深度测试，两种管线由 UI 开关逐帧选择。
- 顶点网格只在网格版本变化时上传；透明度使用 GPU 颜色调制，普通帧不重新调用 `BufferBuilder`。
- 透明度滑块不调用 `markDirty()`，也不会触发顶点缓冲区重传。
- 玩家锚点按 16 格区段对齐，并每 10 tick 检查一次更新。
- UI 以 480×257 的缩放后尺寸复核：
  - 两个滑块、两个并排按钮、清除按钮及说明文字互不覆盖；
  - 状态文字预留完成按钮宽度；
  - 帮助页最后一行位于完成按钮上方。

## API 对照检查

针对 Yarn 1.21.11+build.4，已对照确认源码所用的核心接口存在：

- `RenderPipeline.Builder.withDepthTestFunction(...)`
- `RenderPipeline.Builder.withDepthWrite(...)`
- `RenderPipeline.Builder.withCull(...)`
- `GpuDevice.createBuffer(...)`
- `CommandEncoder.writeToBuffer(...)`
- `RenderPass.setVertexBuffer(...)`
- `RenderPass.setIndexBuffer(...)`
- `RenderPass.drawIndexed(...)`

## 尚需 GitHub Actions 完成的检查

当前本地环境没有 Minecraft/Fabric Gradle 依赖缓存，因此未在本地伪造“完整构建成功”。请上传后由项目内置 GitHub Actions 执行真实的 Loom 编译和 remap。若 Actions 报错，请提供 `compileClientJava` 或运行时崩溃日志。

## 性能说明

本版针对旧版最明显的“每帧重建数百万顶点”问题进行了架构级修改，但实际帧率仍会受到以下因素影响：

- 渲染距离；
- 最终结果表面积；
- 条件数量；
- Sodium、Iris、Voxy 等渲染模组组合；
- 隔墙透明混合产生的像素覆盖量。

极端情况下会在 300000 个面处截断，而不是无限增加 GPU 与 CPU 压力。


## 1.5.1 编译错误修复

根据 Yarn 1.21.11+build.4 API：

- `RenderPipelines` 位于 `net.minecraft.client.gl`；
- `DynamicUniforms` 使用 `write(...)`；
- `RenderPipelines.register(...)` 是私有方法；
- 自定义透墙管线通过复制 `DEBUG_FILLED_BOX` 的公开属性创建。
