# UX-REFACTOR-REPORT — 批v146：UI 与稳定性专项重构交付（ui重构任务书 全阶段）

> 交付日期：2026-11-01 · 基线：v1.0.1-fengx.34（versionCode 36，`d052ac0`）之上
> 依据：`plan/ui重构.md`（948 行任务书 + 7 张结构图）· 审计：`docs/mobile-hub/UX-AUDIT.md`（阶段 0）
> 批号说明：本批与同日「会话内文件上传」批撞号——阶段0-6 的提交信息误标 v145，其中 `c495772` 已推远端、不宜重写已发布历史。正式批号定为 **批v146**，以本报告与 EXECUTION-LOG 条目为准；代码内批号注释已在审查修复提交中统一勘误为 v146。
> **本批不打 tag、不发 Release、不传公开 APK**（主人未授权发版）；只提交 + 推 fork main 跑 CI Verify。版本号保持 1.0.1-fengx.34 / versionCode 36 不变。

## 1. 交付总览（commit 链，均为 fork main）

| commit | 内容 |
|---|---|
| `c495772`¹ | 阶段0+1：UX-AUDIT.md 审计 + 稳定性（archived/cancelled 野状态归位、归档过滤覆盖 status 行、4 条单测） |
| `8274256`¹ | 阶段2+3：任务板重构——四段式分栏、单主操作紧凑卡、详情面板、工具栏、全局设备 scope 去重、unknown 中性区 |
| `b67aeae`¹ | 阶段4+5：派发面板重排 + 真实分支选择 + Agent 列表失败分级 + 想法卡轻量化 + 服务端确认才删草稿 + UNKNOWN 未核查禁止重派 |
| `8e95101`¹ | 阶段6：背景视觉克制（两团 glow 透明度减半） |
| 审查修复（链条末二） | 双子代理对抗审查 21 项发现的收尾（wire 单测钉死、Decoding 语义、墓碑、恢复效应、超时等）+ 代码内批号勘误 |
| 交付文档（本提交） | 本报告 + EXECUTION-LOG 条目 |

¹ 提交信息前缀为 v145（撞号遗留，见头部批号说明）；内容即本批对应阶段交付。

规模：21 文件，+2300/−610 行左右；核心改动集中在 `TaskHubScreen/ViewModel`、`InboxScreen/ViewModel`、`TaskBoard.kt`、`WorkTask.kt`、`CodegClient.kt`、`DispatchService.kt`、`DispatchTargets.kt`。

## 2. 导航与信息架构（任务书 §2）

- 底部五导航（动态/会话/文件夹/搜索/设置）**保留不动**；动态页内 动态|任务|想法 三平级子页保留，数据隔离不变。
- **全局设备选择器只出现一次**：任务板内第二排设备胶囊（DeviceFilterRow）删除，设备范围直接跟随全局栏（单设备模式）或聚合开关（全部模式）——审计 U1/U2 的三份独立筛选状态收敛为一份。
- 文件夹筛选跨机按**名称**匹配（folder id 是每机本地的，名称才可跨设备 travel）。

## 3. 任务板（任务书 §3/§4，对齐作者 Web 端 board-columns.ts）

- **四段式 Segmented Control**：待办=todo,queued · 进行中=preparing,running · 等你处理=awaiting_input,review,merging,failed · 已完成=done,canceled/cancelled（+搁置的 review/failed）。段计数不受搜索词影响（稳定地图）。
- **纵向紧凑卡，每卡一个主操作**：按状态给唯一主按钮（如 review→合并、failed→重试），其余动作进详情面板；按钮禁用不消失（无布局跳动）。
- **工具栏**：文件夹筛选 chip + 搜索 + 「显示已取消/已归档」过滤（Web 同款默认：canceled 显示、archived 隐藏，隐藏数有提示行）。
- **详情面板**：信息栅格 + 按状态出操作（验收三选一：合并/退回(必填反馈)/交付 PR + 归档/搁置/删除）+ 事件/变更文件/按需 Diff；面板按 `serverId:taskId` 做**活查找**（操作后行还在，不显示过期快照）。
- **未知状态绝不归 done**：raw status 原样保留，落独立中性区；`status="archived"` 野行归档化处理（隐藏且不可复活为可运行任务）。

## 4. 派发与想法收件箱（任务书 §5/§6）

- **派发表单顺序**：① 设备与 Agent（双滚轮，Agent 列表=设备真列表，失败只显「获取失败+重试」短提示，原始 ACP 错误只进日志）→ ② 任务描述 → ③ 目标项目+分支 → ④ 定时/守卫/创建。
- **真实分支选择**（上游真支持才做）：`git_list_all_branches(path)` 拉分支 → `config.base_branch`；分支列表用 `serverId:folder.path` 键钉死当前仓库，切换设备/项目自动失效；加载失败保持「跟随项目默认」仍可派发（红线：上游不支持就不假装支持——先核实了服务端字段才接 UI）。
- **想法生命周期（结果未知不盲目重发）**：
  - 只有 `DispatchOutcome.Confirmed` 才删本地草稿；删除失败保留草稿并明示「请勿再次派发该想法」。
  - UNKNOWN 卡的「重新派发」**三重门禁**：UI 禁用+提示 → `probeClearedIds` 状态位 → VM 层再拦一次。只有探查**精确返回 0 命中**才解锁；其余一切结果（失败/多命中/超时）在 finally 里收回解锁。
  - 探查时间窗 0 命中时加**全列表同名兜底**：同项目存在同名任务（时间不在窗内）→ 判「无法确认是否本条」，不解锁；全列表 0 同名才判「未创建」。
  - 探查写回 CONFIRMED 前做**墓碑检查**（ref 已被用户放弃则不复活）；派发服务端 confirm 同样只写活行（putIfPresent）。
- **结果对话框提升到 Screen 层**：修复确认成功删想法→面板连带对话框一起卸载、成功提示一闪而过的 bug（对抗审查 B-7）。
- **进程死亡恢复**：rememberSaveable 恢复的设备/项目/Agent 选择，配三个 LaunchedEffect 补 loadTarget、清失效 Agent、清失效项目（B-5）。
- 项目列表失败与 Agent 列表同款「短提示+重试」（foldersError，新增字符串双语）。

## 5. 状态兼容与 wire 契约（本批最重要的根因修复）

- **亲手核对服务端 Rust 源码定死 ground truth**（`repo/src-tauri/src/web/handlers/work_task.rs`）：**所有**顶层 params 结构都带 `rename_all="camelCase"` → wire 是 `deleteWorktree`/`scheduledAt`；其中 **Merge/Complete 的 deleteWorktree 无 serde default（必填）**——发 snake_case 会被 serde 静默丢弃然后 422。snake_case 例外只有**嵌套**载荷：WorkTaskDraft（`folder_id`）与 WorkTaskConfig（`prompt_blocks`/`agent_type`/`base_branch`…）。
  - 本批曾一度把方向搞反（阶段2+3 提交改成 snake_case），靠重读源码纠正回 camelCase。**新增 `WorkTaskWireTest`（7 例）把全部 wire key 钉死**——这个事故源发作过两次，以后再犯是红测试不是线上 422。
- **搁置（defer）对齐上游规则**：只有 `deferred && status∈{review,failed}` 才算有效搁置归入已完成（服务端 `set_deferred` 的 DEFERRABLE_STATUSES 同口径）；stale deferred_at 不再误归类；UI 的搁置/取消搁置按钮同样按此门控。
- `ApiError.Decoding`（send 成功但响应解不开）= **结果未知**，走探测/UNKNOWN 路径——之前按硬失败删台账，恰好删掉唯一线索、招致双创建（审查 #1）。
- 列表解码丢弃行打日志（可排查不静默）；`work_task_list` 超时与「0 任务」严格区分（v139 规则保持）。

## 6. 多服务器与稳定性

- 聚合 fan-out：每设备独立提交、失败保留该机旧切片+横幅可重试；**聚合超时 3s→10s**（冷 DERP 中继首屏误标 FAILED 的实测问题；各设备独立提交所以不拖慢他机首屏，单设备页相应 4s→12s 保持「单设备更宽」不变量）。
- 用户主动刷新 bump generation：操作后的刷新不会被在途轮询作废；任务详情 Diff 分支补 epoch 守卫（旧响应不回填）。
- 看板投影 `flowOn(Dispatchers.Default)`：分组/过滤计算移出主线程。
- merge 排队（同项目串行合并槽）返回 true 时给「已加入合并队列」提示行（actionNote，点按消失）。

## 7. 视觉（任务书 §8）

- 背景两团 radial glow 透明度减半（0.20/0.18 → 0.10/0.09），保留签名感但不与内容抢视觉；深浅色同比例收敛。
- 其余按任务书保持克制：无新增发光/渐变；深色主题为主。

## 8. 测试与构建

- **单测：双 flavor 全量 928 例（464×2）0 失败**。新增：WorkTaskWireTest 7 例（wire key 钉死）+ TaskBoardTest 重写（Web 口径分段/搁置/野状态/过滤/计数/跨机聚合 ~15 例）+ 阶段1 稳定性 4 例。
- **编译：assembleModernRelease + assembleLegacyRelease 双绿**（本地验证用，APK 不外发）。
- **secret 扫描：diff 无 Token/密钥/内网 IP**（唯一命中是「Token 缺失」错误文案）。
- CI：推 fork main 触发 Verify 工作流（同门槛）。

## 9. 未解决与已接受风险（如实列出）

1. **IdeaStore/DispatchStore 解码失败→空列表**：存储损坏时账本被静默遮蔽（DataStore 单 key JSON 的固有面）。概率极低，修复需要迁移存储格式，本批不动（B-10 接受）。
2. **探查兜底后的残余窗口**：全列表同名兜底把「误判未创建」窗口压到近零但非零——若服务端在「创建成功→探查之间」任务又被删，仍可能解锁重派。上游无幂等键，客户端无法根除。
3. **交付 PR 全局忙碌锁**：服务端等 PR 出门可能数分钟，期间整板操作锁死。已把确认弹窗文案改为明示「可能需要数分钟」；真正并发化需要改 busyKey 粒度，留待下批。
4. **dell/win 服务端版本错位**（旧版对 `archived` status 行 500 / 字段形状旧）：需要主人单独授权升级各机服务端，APP 端已做兼容投影（野状态隐藏、缺字段 null 容忍）。
5. 任务详情在 merging 态的操作按钮仍按状态白名单显示，合并完成前的竞态由服务端串行合并槽兜底。

## 10. Git 状态与产物

- 分支：fork main，领先 origin（上游）不受影响；推送后 CI Verify 自动跑。
- 本批 commit：`c495772` → `8274256` → `b67aeae` → `8e95101` → 审查修复 → 交付文档（本文档）；前四个提交信息前缀 v145 为撞号遗留（见头部批号说明）。曾尝试 filter-branch 整链重编号，因 c495772 已在远端会强制推送而回退（备份分支 `backup-v146-rewritten` 留档，确认后可删）。
- APK：本地验证产物在 `app/build/outputs/apk/{modern,legacy}/release/`，**未发布**；装新版本需主人授权后走 GitHub Actions Release 产线（改版本号+打 tag）。
