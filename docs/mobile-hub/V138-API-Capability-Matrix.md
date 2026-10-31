# V138 API 能力矩阵

> 审计对象：`/mnt/Projects/codeg/repo/src-tauri`（axum 服务端模式）。全部路由注册于 `src-tauri/src/web/router.rs`，挂载 `/api` 前缀（router.rs:2194），除 public 路由外均过 token 中间件（router.rs:2063-2065）。所有 HTTP 均为 POST + JSON（camelCase）。WS 为 `GET /ws/events`。
> 结论：✅=可直接用；⚠=存在但受限；❌=不存在，需降级。

## 1. 连接与鉴权 — ✅
- `Authorization: Bearer <token>`（`web/auth.rs:39-47`）；WS 用 `Sec-WebSocket-Protocol: codeg-events, <token>`（auth.rs:12,47-52）。
- `POST /api/health` → `{status, version}`（router.rs:2245）——**是 POST 且在鉴权层之内**，探活必须带 token。
- Android 已封装：`CodegClient`（30s 超时）+ `EventStream`（subprotocol 鉴权）。

## 2. 项目列表 — ✅
- `POST /api/list_folders` → `Vec<FolderInfo>`（handlers/conversations.rs:208）；`list_all_folder_details` / `get_folder` / `open_folder_by_id`。

## 3. Agent/CLI 列表 — ✅
- `POST /api/acp_list_agents` → `Vec<AcpAgentInfo>`（handlers/acp.rs:39）；`acp_get_agent_status {agentType}`；`acp_preflight {agentType, forceRefresh?}`。

## 4. 模型列表 — ✅
- `POST /api/acp_describe_agent_options {agentType, workingDir?, configValues?}` → `AgentOptionsSnapshot`（内含模型与 config options）。
- Provider 目录：`list_provider_models` / `list_model_providers`。

## 5. Agent 动态配置 — ✅
- 读：`acp_describe_agent_options`（acp.rs:420）。写：会话内 `acp_set_config_option` / `acp_set_mode`；持久化 `acp_update_agent_config` / `acp_update_agent_preferences` / `acp_update_agent_env`。

## 6. Session 列表与详情 — ✅
- `POST /api/list_all_conversations {folderIds?, agentType?, search?, sortBy?, status?, includeChildren?}` → `Vec<DbConversationSummary>`（conversations.rs:13）。
- 详情：`get_conversation` / `get_folder_conversation {conversationId, tailTurns?|fromIndex?}`；分页 `get_folder_conversation_turns {conversationId, beforeIndex, limit}`；大纲 `get_folder_conversation_outline`。

## 7. To-do 列表 — ✅（无分页 ⚠）
- `POST /api/work_task_list {folderId?}` → `Vec<WorkTaskInfo>`（handlers/work_task.rs:212）。`work_task_get {id}`；徽标 `work_task_attention_count`。
- DTO：`models/work_task.rs:9-108`（status/verdict/resultSummary/filesChanged/additions/deletions/scheduledAt/archivedAt/connectionId…）。
- ⚠ 无分页、无状态过滤参数；服务端只滤软删（`work_task_service.rs:176-191`）。Android 全量拉取+本地过滤。

## 8. To-do 创建 — ✅（无幂等 ❌）
- `POST /api/work_task_create {"draft": WorkTaskDraft}` → 完整 `WorkTaskInfo`（含 id）。
- `WorkTaskDraft`（models/work_task.rs:128-132）：`folderId: i32`、`title`、`config: WorkTaskConfig`（内部 models/work_task.rs:173-207：`promptBlocks`、`displayText`、`agentType?`、`modeId?`、`configValues` map、`deliverable?`、`baseBranch?`）。
- ❌ 无幂等键字段。重复 POST = 重复任务。降级：见 V138-Implementation-Plan §幂等。

## 9. 状态枚举与流转 — ✅（以源码为准）
枚举（`db/entities/work_task.rs:17-47`，serde snake_case）：
`todo, queued, preparing, running, awaiting_input, review, merging, done, failed, canceled`
- 流转：`todo → queued → preparing → running ⇄ awaiting_input → review → merging → done`；failed/canceled 旁路；canceled 可 requeue 回 todo。
- `done` ⟺ 最终成功（completion_kind ∈ merged|delivered_pr|accepted_without_merge），必经 review，永不回退。
- `preparing`（不是 "setting up"）= 建 worktree+init+启动 CLI；`merging` 唯一不可取消。
- 另有非状态字段 `deferred_at`（稍后处理，端点 `work_task_defer`）。
- 所有迁移为带期望状态的 CAS UPDATE。

## 10. 定时派发 — ✅
- `work_task.scheduled_at: Option<DateTimeUtc>`；`POST /api/work_task_schedule {id, scheduledAt?}`（RFC 3339，null=清除；handlers/work_task.rs:346）。
- 服务端 `parse_from_rfc3339` 后转 UTC 存储（commands/work_task.rs:496-513）——**客户端负责换算成绝对时刻**。
- 仅 `todo` 状态可设计划，否则 Validation 错误。
- 调度器存在：`work_task/engine.rs:494-529` 主循环 `claim_due_scheduled`（SCHEDULE_INTERVAL_SECS tick，关机错过重启后补跑，claim+清除 scheduledAt 同事务，恰好触发一次）。过去时间设计划会立即 nudge。
- 定时 ≠ 创建时即定时：先 create（todo）再 schedule。启动路径：`work_task_start` / `work_task_start_all` / 调度器。

## 11. 服务端时区 — ❌
- 无任何接口返回服务器/调度时区。`scheduled_at` 是绝对 UTC 时刻，不涉时区。
- 降级：Android 端用设备时区选择本地时间→换算 UTC 绝对时刻发送，UI 标注"按手机时区 xx 换算"。Automations 的 IANA timezone 字段仅限自动化规则，与 work_task 无关。

## 12. 幂等提交 — ❌
- `work_task_create` 无幂等键；`client_message_id` 仅存在于 `acp_prompt`（消息侧）。
- 降级：本地 request_id 记账 + 超时后用 `work_task_list` 回查（标题+folderId+创建时间窗匹配）+ "提交结果未知"人工确认，见 Implementation-Plan §防重复。

## 13. WebSocket 事件 — ✅
- 通道清单（`web/event_bridge.rs`）：`task://changed`(:446，payload 只带 id)、`conversation://changed`、`folder://changed`、`automation://changed`、`conversations://bulk-changed`、`tabs://changed` 等约 19 个。
- ⚠ **看板无专用事件**：`task://changed` 只给 id，客户端需 HTTP 回查 `work_task_get`（或节流后整表刷新）。

## 14. 断线恢复 — ✅（限会话事件）
- attach 协议（`web/ws_attach.rs`）：`{action:"attach", subscriptionId, connectionId, sinceSeq?}` → `snapshot`/`replay`/`event`/`detached{reason}`。reason: `connection_gone`(终态)/`lagged`(带 lastAppliedSeq 重连)/`server_shutdown`。
- 仅按 connectionId 订阅 ACP 会话，无全局订阅。HTTP 兜底：`acp_get_session_snapshot[_by_conversation]`。
- 看板恢复 = 重连后全量 `work_task_list`。

## 15. 权限审批 — ✅
- `acp_respond_permission {connectionId, requestId, optionId}`；计划审批 `acp_answer_plan_approval`。Android 已有 `Interactive.kt` 模型与三张卡片组件。

## 16. Agent 问题回答 — ✅
- `acp_answer_question {connectionId, questionId, answer}`。已封装。

## 17. Diff — ✅
- 任务级 `work_task_diff {id, file?}`、`work_task_changed_files {id}`；通用 `git_diff`/`git_show_diff` 等。Android 有 `DiffView` 组件。

## 18. 取消 — ✅
- `work_task_cancel {id, reason?, deleteWorktree?}`；`work_task_retry` / `work_task_requeue`（可带 note/blocks）；会话级 `acp_cancel`。

## 19. 验收与合并 — ✅
- `work_task_merge {id, message?, deleteWorktree, instructions?}` → bool（true=进合并槽排队）；`work_task_merge_unqueue`；无变更验收 `work_task_complete {id, deleteWorktree}`；PR 交付 `work_task_deliver_pr`；退回修改 `work_task_return {id, feedback, intent?, blocks?}`。

## 20. 任务历史 — ⚠
- 已完成任务含于 `work_task_list` 全量返回；归档 `work_task_archive {id, archived}`；每任务事件 `work_task_events {taskId, limit?=500}`。
- ❌ 无跨任务分页/时间范围端点 → Android 本地过滤分页。

## 附加 A-D
- **A**：create 返回完整 `WorkTaskInfo`（含 id），可立即建立本地关联。
- **B**：创建后停留 `todo` 不自动启动；`queued`=等 folder `max_concurrent` 并发槽（0=不限），`pump_folder` 每 folder 互斥；合并每 folder 单槽。
- **C**：transcript 分页可拉全（见 §6）；`acp_fork {connectionId, conversationId?, folderId?, forkFromTurnId?}` 是现成的"从会话分叉续聊"原语；**无 steering 通道**（turn 中再 prompt → 409 TurnInProgress，本地 fork 版 v121 的 steering 依赖比本地 repo 快照新的服务端，见 Risk-Register）。
- **D**：`git_status {path}`、`get_git_branch {path}`、`get_git_head {path}`（handlers/git.rs:74、folders.rs:333）——注意参数是**文件系统 path**，需先由 folder 详情取路径。

## 服务端版本风险
本地 repo 快照（125 路由，RouteParityTest 基线 553 行）可能落后于四台设备实际部署版本。施工前在真机上对四台跑 `POST /api/health` 取 version 对账；缺端点的设备按能力矩阵隐藏对应操作（绝不发假按钮）。
