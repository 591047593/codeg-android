# V138 实施计划（v139-v142 施工图总纲）

## v139 任务状态中心（P0）
**目标**：一个页面看四台设备的 work_task 看板。
- `CodegClient` 新增封装：`workTaskList/workTaskGet/workTaskStart/workTaskCancel/workTaskRetry/workTaskRequeue/workTaskMerge/workTaskComplete/workTaskDiff/workTaskChangedFiles/workTaskEvents/workTaskSchedule/workTaskDefer` + `gitStatus/getGitBranch`。同步 `tools/_server_routes_snapshot.txt`（RouteParityTest 会拦）。
- `core/data/TaskHubStore.kt`：本地缓存（DataStore JSON，server_id→tasks），纯展示缓存非事实源。
- `core/data/TaskBoard.kt`：纯函数四列投影（见 Gap-Analysis 映射表）+ 未知状态兜底 + 事件去重（task://changed id 集合节流回查）。配 TaskBoardTest。
- `feature/taskhub/TaskHubViewModel.kt`：复用 DeviceLoad 轮转骨架（每设备独立超时+重试）；监听各设备 `task://changed` → 节流回查；设备/项目/Agent 筛选；历史=本地过滤。
- UI：`TaskHubScreen`（四列/列表切换 + GlobalDeviceBar + 共享错误横幅）；任务详情：状态/结果摘要/changed files/Diff（复用 DiffView）/事件历史；操作按状态显示（retry/cancel/merge/complete/approve），401 设备显示"Token 失效"。
- 入口：HomeTab 增加 TASK？→ 否，避免动五栏枚举；入口挂 ACTIVITY 页顶部切换 + 设置默认页可选。最终以实施时最小侵入为准。

## v140 想法收件箱 + 统一派发
- `core/datastore/IdeaStore.kt`（draft_id/title/description/created_at/updated_at/source_*）+ IdeaDraftTest。
- 收件箱页（复用列表骨架 + CenteredActionMenu 长按：编辑/复制/删除/派发）。
- 派发向导：设备→项目→Agent→模型（describe_agent_options 动态）→标题/描述→可选定时。
- 派发管线 `core/data/DispatchPipeline.kt`（纯函数可测）：
  1. 预检（health/folder/agent/model/git_status；不可验证标"未验证"）
  2. `request_id`（UUID）+ DispatchRefStore 记账（PENDING）
  3. create → 成功写 remote_todo_id=DONE；**超时/断网 → 回查 work_task_list（folderId+title+时间窗）匹配 → 找到=DONE，找不到=UNKNOWN → UI"提交结果未知"人工确认，绝不自动重发**
- 定时：create 成功且状态 todo → `work_task_schedule`（本地时区→UTC RFC3339，UI 标注"按手机时区"）；失败可重试 schedule 不影响任务已创建。
- 入口：首页快捷卡 + 设置。

## v141 会话交接
- 交接包模型 `core/model/HandoffPackage.kt`（12 字段）+ 模板文本生成纯函数。
- 生成：会话详情长按 → "生成交接包"：优先拉 `get_folder_conversation_turns` 近 N turns 填模板，用户在编辑器改；会话内已有"接手引用"（批五）可复用文案。
- 目标端选择/预检/派发完全复用 v140 管线（交接包=描述文本，模式 A）；模式 B 加 git 预检步骤（branch/未提交数）并强提示。
- 空包/超长/特殊字符验收。

## v142 双手机配置同步
- `core/security/ConfigSyncCrypto.kt`（纯 JVM 可测）：PBKDF2-HMAC-SHA256（600k，参数上限防 DoS：iterations≤1M、salt≤64B、ciphertext≤1MB）、AES-256-GCM、SecureRandom、Base64 URL-safe 单段文本、外层 JSON 带 format/version/kdf/iterations/cipher/salt/nonce/ciphertext。
- 导出：SecretStore 读出 token → JSON → 加密 → 复制；全程无网络。导入：粘贴→密码→解密→schema 校验→冲突列表（server_id 撞车让用户选）→事务性合并（先内存构建全量 profiles 再一次写入）。
- 设置入口 SettingsLeaf 加"配置同步"。
- 测试：正解/错密码/篡改/坏版本/坏 JSON/缺字段/大配置/冲突合并。

## 全局纪律
- 每批：实施 → 双重审查（对抗代码审查 + 墨菲极端场景，子代理并行）→ 修复 → `:app:check` → commit → push main → tag 发版。
- 薄客户端红线：不建第二状态机、不自行调度、服务端状态原样保存。
- 真机对账：施工前对四台 `POST /api/health` 记录 version；端点缺失按能力隐藏。
