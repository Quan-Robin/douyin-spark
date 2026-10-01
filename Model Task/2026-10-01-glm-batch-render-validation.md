# GLM 5.3 Flash 派单：多场景渲染批量验证

## 目标

为 WS 独立 scene renderer 补充可重复的批量验证，证明 composite 快照修复没有只优化单张壁纸。

## 范围

- 仓库：`D:\AI\DSH\wallpaper-schedule`
- 只新增验证脚本或报告，不修改渲染器核心、不引入 NuGet。
- 使用真实 Steam workshop scene 的 `project.json + scene.pkg`，保留 Steam/WE 原文件。

## 要求

1. 覆盖至少 8 个已有代表性 scene，至少包含 `3354366708`、`3415297400`、`3576228383`、`3610728777`，固定 `--scene-time 1`。
2. 每个 scene 记录 exit code、PNG 尺寸、RGBA、ffprobe/ffmpeg 解码、非全黑、scene layers/omitted/warnings。
3. 对含 `_rt_imageLayerComposite_*` 的 scene 单独标记 composite hit 数量和诊断；区分旧历史产物与新产物。
4. 若发现失败，只报告最小复现命令和日志，不自行改核心代码。

## 交付

报告落盘：`D:\AI\DSH\Model Task\2026-10-01-glm-batch-render-validation-report.md`

需贴出真实命令和汇总输出，并列出剩余风险。
