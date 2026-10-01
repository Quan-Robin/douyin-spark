# Claude Opus 5.5 派单：WS 跨层 Composite 数据流审查

## 目标

审查 `D:\AI\DSH\wallpaper-schedule` 独立 Wallpaper Engine scene renderer 的跨层 composite/render-target 修复方向，重点关注 3415297400 的星盘错位与红色重影。

## 输入与范围

- 只读审查：`src/Ws.Platform.Windows/Dynamic/SceneRenderer.cs`、`ScenePlan.cs`、`SceneEffects.cs`、`SceneCompositeGraph.cs`、对应测试和 `docs/AGENT-HANDOFF.md`。
- 对照本机 `.tmp-current/open-we` 的公开实现和已记录 GPL-2.0 许可证边界。
- 不修改仓库、不提交、不复制 GPL 代码、Wallpaper Engine 二进制或素材。

## 要求

1. 解释 `_rt_imageLayerComposite_<id>_[a|b]` 的 layer output 语义、源层可见性和 z 顺序。
2. 检查隔离源层、空 composelayer、self-reference、缺失源层、循环依赖和 effect ping-pong 的方案。
3. 给出最小可验证的实现建议和回归 fixture，避免只为 3415297400 打补丁。
4. 必须区分已从源码/测试确认的事实、合理近似和未知项。

## 交付

请将报告落盘到：

`D:\AI\DSH\Model Task\2026-10-01-claude-composite-render-review-report.md`

报告末尾列出实际执行的命令、输出摘要、未完成项、风险和下一步建议。
