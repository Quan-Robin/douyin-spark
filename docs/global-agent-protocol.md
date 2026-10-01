# 全局 agent 协议：存储位置与结构决策

本文件记录「全局工作协议」存放位置与结构的决策，供后续会话查证。它不是行为规则本身。

## 一句话

行为规则本体在 `C:\Users\quang\.dsh\AGENTS.md`（DSH 全局指令文件，每个会话自动注入）；本文件只记录它为什么在那里、和 Codex 那份的关系。

## 关键事实：两套全局文件互不相通

| 文件 | 谁读取 | 状态 |
| --- | --- | --- |
| `C:\Users\quang\.dsh\AGENTS.md` | DSH（`$DSH_HOME/AGENTS.md`） | 现行本体，已生效 |
| `C:\Users\quang\.codex\AGENTS.md` | Codex 自己的加载器 | 原始 415 行版本，未改动 |

- `$DSH_HOME` = `C:\Users\quang\.dsh`，DSH 的全局槽位就是该目录下的 `AGENTS.md`；编辑 Codex 那份**不会**影响 DSH，反之亦然。
- 项目级链路：从项目根（以 `.git` 标记）到会话工作目录的每一层，加载 `AGENTS.md` 与 `CLAUDE.md`，另有 `*.local.md` 叠加层；同级文件内容相同只渲染一次。
- 注入顺序由宽泛到具体（全局 → 项目根 → 更深目录），预算 65536 字节，超预算时先丢弃宽泛文件、最后截断最具体文件。
- 成功 read/write/edit 触及更深目录后，该目录的指令文件在下一个请求生效。

## 本次优化做了什么

把 Codex 版 415 行协议压缩为 39 行、9 个小节，字符数约 8257 → 3800（约 -54%），占用全局预算约 10%。

- **保留**：优先级、需求合理性判断、`目标→观察→风险→替代→确认` 质疑结构、最小改动、验证闭环与完成定义、决策／踩坑／否决点记录、沟通与交接、安全边界。
- **改为按需触发**：三文档体系（docs/direction.md、decisions.md、blueprint.md）不再要求每个任务先读三份，只在任务涉及方向、非目标、已否决方案或历史踩坑时读；文档不存在则不创建。GitHub 竞品调研保留为「新项目或重大功能」触发项。
- **删除**：目录树、签名、节号，以及为解释规则而写的论证性文字；多处重复表述（如「最小改动」原文出现 4 次）合并。
- **新增**：DSH 运行机制小节（指令文件层级、文件沙箱越界需审批、Hindsight 记忆用法、Agent Teams 分工），这些在原 Codex 文件中不存在。
- **补回**：原第 10 节的提交纪律（小 diff、单一目的、说明「为什么」、不混入无关修复），压缩为一条。

## 验证（2026-02-14）

- 写入 `C:\Users\quang\.dsh\AGENTS.md` 成功；同一会话中 DSH 立即以 `Instructions from: ~/.dsh/AGENTS.md` 注入基线，两处编辑各触发一次 `Updated instructions from: ~/.dsh/AGENTS.md`，确认加载器已实际接管。
- 环境事实：`.dsh` 目录位于会话工作区之外，写入需要一次性提权（`danger-full-access`）；plain workspace-write 会被拒绝。

## 待决

- **记忆未落地**：Hindsight 不可用——`C:\Users\quang\.hindsight\coding-agent.json` 不存在且 API token 未配置，`ingest_document` 返回 401，故本次改用本文件作为可查证的镜像记录。配置 token 后可重跑入库。
- **是否同步 Codex**：Codex 那份仍是 415 行旧版。若要两者共用一份，可用 `mklink /H` 硬链接或 junction，但会改变 Codex 的现有行为，需用户确认。
- 最终文件大小将在交付时实测；本文件不写未实测的数字。
