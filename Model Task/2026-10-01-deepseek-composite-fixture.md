# DeepSeek 派单：Composite 合成最小回归 Fixture

## 目标

设计一个零依赖 synthetic scene fixture，验证 `_rt_imageLayerComposite_<id>_a` 使用源层输出，而不是当前层输入或整张画布。

## 范围

- 仓库：`D:\AI\DSH\wallpaper-schedule`
- 只新增 `tests/Ws.WeSceneTests/` 下的 fixture/test 代码或说明；不修改 `SceneRenderer.cs`、不引入 NuGet。
- 颜色使用明显不同的源层/消费者层（例如红源、蓝底），覆盖正常源层、空 composelayer、self-reference、缺失源层。

## 要求

1. 先阅读现有测试 harness 和 `SceneCompositeGraphTests`，复用现有风格。
2. 断言必须能区分：源层输出、当前层输入、全画布 framebuffer 三种错误实现。
3. 报告实际运行的构建/测试命令和输出；不要把未运行的结果写成通过。

## 交付

如果新增测试，落盘在仓库测试目录；同时写报告到：
`D:\AI\DSH\Model Task\2026-10-01-deepseek-composite-fixture-report.md`
