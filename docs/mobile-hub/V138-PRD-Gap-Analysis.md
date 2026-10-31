# V138 PRD 差距分析（PRD ↔ 实测能力逐条映射）

图例：✅已有 / ♻️可复用改造 / 🆕需新增 / 🚫上游不支持（含降级）

| PRD 需求 | 结论 | 依据/降级 |
|---|---|---|
| 多服务器统一连接（≥4 台，数量不硬编码） | ♻️ 基本已有 | ServerRepository 已支持任意数量；v137 渐进加载+跳过设备已落地 |
| 七种连接状态区分 | 🆕 | 现状只有粗粒度 OK/FAILED；v139 顺手补 token 失效(401)/WS 断开标记，不做完整七态 |
| 版本与能力检测 | 🆕 轻 | `POST /api/health` 返回 version；按能力矩阵隐藏缺端点的操作 |
| 单台故障不阻塞其他 | ✅ | v137 DeviceLoad 机制 |
| 想法收件箱（本地草稿，派发后关联） | 🆕 | IdeaStore（DataStore，仿 DraftStore）；派发后存 target_server_id/project_id/remote_todo_id |
| 会话交接包（12 字段，可编辑） | 🆕 | V1 轻方案：拉 transcript 分页 + 手动编辑模板；不做后台自动总结（PRD §17 排除） |
| 模式 A 上下文接力 | 🆕 | 交接包文本 → work_task_create 的 config.promptBlocks |
| 模式 B Git 接力 | 🆕 | `git_status`/`get_git_branch` 预检（需 path）；未提交修改强提示；不自动同步代码 |
| 原生 To-do 派发 | 🆕 | `work_task_create`；config 支持 agentType/modeId/baseBranch/configValues，按 acp_describe_agent_options 动态展示 |
| 目标端预检 | 🆕 | health/list_folders/acp_list_agents/describe_agent_options/git_status；不可验证项标"未验证" |
| request_id + 防重复提交 | 🆕 降级 | 🚫服务端无幂等键 → 本地记账 + 超时回查（work_task_list 标题/时间窗匹配）+ "结果未知"确认 |
| 定时派发（服务端原生调度） | 🆕 | 先 create 后 `work_task_schedule`（RFC3339 UTC）；调度器真实存在（含补跑） |
| 时区 | 🚫 降级 | 无服务器时区接口 → 手机本地时区换算成绝对 UTC 时刻，UI 标注换算依据 |
| 四列投影（待执行/执行中/需要处理/已完成） | 🆕 | 状态映射：todo+queued → 待执行；preparing+running → 执行中；awaiting_input+review → 需要处理；done/merging → 已完成（merging 归已完成列但带"合并中"标签）；failed/canceled → 终态单独行；未知状态 → 原样显示，绝不归 done |
| 未知状态兼容 | 🆕 | 保存 server_status 原文 + displayColumn 推断，未知→"未知"列 |
| 任务操作（审批/回答/Diff/取消/验收/合并） | 🆕 | 全套端点存在（含 deliver_pr/complete/return/retry/requeue）；危险操作二次确认 |
| 任务历史+筛选 | 🆕 降级 | 无分页 → 全量 list + 本地筛选（设备/项目/Agent/时间）；事件历史 work_task_events |
| 前台 WS 实时 | ♻️ | legacy firehose `task://changed`（id-only）+ HTTP 回查；attach 协议仅会话事件 |
| 断线恢复 | 🆕 轻 | 看板：重连全量刷新；会话：已有 snapshot/replay |
| 双手机配置同步（AES-256-GCM+PBKDF2 剪贴板） | 🆕 | 完全本地；注意 SecretStore 需导出 token；PBKDF2 600k 迭代低端机实测后可调 |
| 后台推送 FCM | — | PRD 已推迟，不做 |

## PRD 需修正的认知
1. 状态名 `setting up` 实为 `preparing`；状态全集见矩阵 §9。
2. "定时任务 API 所要求格式" = RFC3339 绝对时刻，且定时是创建后的第二个调用。
3. work_task 与会话(conversation)是两套体系：work_task 有自己的 worktree/生命周期，通过 connectionId/conversationId 关联。四列看板的数据源是 work_task，不是动态页 Activity。
4. 服务端版本快照可能落后于真机部署，施工前对四台 health 对账。
