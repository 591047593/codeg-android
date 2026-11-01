# UX-AUDIT — Codeg Mobile Hub V1 现状审计（ui重构任务书 阶段 0）

> 审计日期：2026-11-01 · 基线：v1.0.1-fengx.34（versionCode 36，commit f342997/c99b84e 之后）
> 依据：`plan/ui重构.md` 任务书 + 作者 Web 端（unixcs codeg 最新版）交互 + 真机截图（Android 9，legacy 包）。
> 结论口径：哪些已实现、哪些有缺陷、哪些缺失；所有断言均带 文件:行号。

## 1. 当前页面结构

- 底部导航五入口（动态/会话/文件夹/搜索/设置）：`feature/main/MainShell.kt`，符合任务书 §2.1，**保留不动**。
- 动态 tab 内部三子页（动态|任务|想法）：`feature/activity/ActivityScreen.kt:100` `pageMode`(0/1/2, rememberSaveable)，TabRow :188-201，惰性渲染 :202-210。符合 §2.2。
- 全局设备选择器：`core/designsystem/component/GlobalDeviceBar.kt:85`，由 MainShell.kt:722 挂在每个 tab 内容上方；状态在 `core/data/ServerRepository.kt:56`（selectedId，DataStore 持久）+ `:80-83`（activityAll 聚合开关，进程内）。
- 任务板：`feature/taskhub/TaskHubScreen.kt`（约 600 行）+ `core/data/TaskBoard.kt`（六列分组）。
- 想法收件箱：`feature/inbox/InboxScreen.kt` + `InboxViewModel.kt`；存储 `core/datastore/IdeaStore.kt`（DataStore Preferences，整列表 JSON 进单 key `"idea_inbox"`，非 Room）。
- 派发管线：`core/data/DispatchService.kt`（幂等账本 DispatchStore + 结果三态 PENDING/CONFIRMED/UNKNOWN + v34 起"服务端明确拒绝=确定性失败、真未知才探测自愈"）。

## 2. 主要 UX 问题（对照任务书）

| # | 问题 | 位置 | 任务书条款 |
|---|---|---|---|
| U1 | 任务页内有**第二排设备胶囊**（DeviceFilterRow），与全局设备栏重复且状态不联动（全局选了 dell，任务板仍是"全部"） | TaskHubScreen.kt:244-291；状态 TaskHubViewModel.kt:84,168 | §2.3 |
| U2 | 设备筛选共**三份独立状态**：ServerRepository.selectedId+activityAll / ActivityViewModel poll 组合 / TaskHubViewModel.deviceFilter。同源不同步 | ServerRepository.kt:56,80；ActivityViewModel.kt:138-144；TaskHubViewModel.kt:84 | §2.3 |
| U3 | 任务分组是六列纵排（待处理/执行中/需要处理/已完成/失败取消/未知），与 Web 端四列口径不一致；failed 在 APP 单列、Web 归"等你处理"；canceled 在 APP 单列、Web 归"已完成" | TaskBoard.kt:23-49 | §4.2 |
| U4 | 任务卡片底部平铺大量操作按钮（开始/取消/合并/完成/重试/重新入队全按状态铺开），无单一主操作；无文件夹选择/搜索/筛选（显示已取消/已归档）工具栏 | TaskHubScreen.kt:446-485 | §4.1/§4.3 |
| U5 | 想法卡片四按钮平铺（派发/编辑/复制/删除），像管理后台 | InboxScreen.kt（卡片操作行） | §5.2 |
| U6 | **派发确认成功后想法永不移除**——全仓库无 dispatch 路径上的 ideaStore.delete；确认后只弹成功框，草稿永久滞留 | DispatchService.kt:229-272；InboxScreen.kt:627,643-659 | §5.1/§5.4-9 |
| U7 | UNKNOWN 结果卡提供"重新派发"按钮，未先核实即可再次创建，违反"未核实前不能直接再次创建同一任务" | InboxScreen.kt:327-367 | §5.4 |
| U8 | 错误原文直出：FailedBanner/actionError/dispatchError 等把服务端原始报错（含 `Database operation failed（...archived...）`）整句展示，无分级、无可展开的技术详情 | TaskHubScreen.kt:119-131,294-317; InboxViewModel.kt:296 | §7.4/§6.3 |
| U9 | 背景两团 radial glow（accent 0.20 + 靛蓝 0.18），页面氛围偏"炫技概念 Demo" | core/designsystem/CodegBackground.kt:32-67 | §8 |
| U10 | 新建/派发表单顺序低效（设备→项目→agent→标题→描述），无分支选择（DispatchPipeline.taskConfig 已预留 base_branch 参数但 UI 未接）、无 agent 预填 | InboxScreen.kt DispatchSheet(:407 起)；DispatchPipeline.kt:32,49 | §6.2 |

## 3. 数据流与状态流（要点）

- 设备→数据：单设备模式 ActivityViewModel poll `combine(selectedProfile, activityAll, refreshTick)`（ActivityViewModel.kt:138-144）；聚合模式 fetchAllDevices()（:496-497）并发拉 `profiles.filter{visible && includeInAll}`，行自带 serverId/serverName。任务板同理多设备并发加载，失败按设备聚合进 FailedBanner，单设备失败不阻塞他机（已满足 §7.3 主体）。
- 任务状态：`WorkTaskInfo.status` 为**原始 String**（core/model/WorkTask.kt:20-24），KNOWN_STATUSES（:44-47）白名单 + 未知→`"unknown"`，TaskBoard UNKNOWN 列兜底、绝不归为完成（TaskBoard.kt:42-49）。**APP 端不存在严格枚举反序列化崩溃面**；无 Room（想法是 DataStore JSON），无 TypeConverter 风险。
- 派发结果：DispatchOutcome Confirmed/Unknown/HardFailed；HardFailed 清账本（DispatchService.kt:195），Confirmed 只置 ref 状态（:229-272）。v34 已把"服务端返回了响应（4xx/5xx）"判为确定性失败（isAmbiguous 仅剩 IOException/Transport），真未知才 1.5s 后探测自愈。
- 想法隔离：想法只存本地 DataStore；新建想法不会触网；服务端任务状态不写入想法字段（DispatchRef 是独立账本）——§5.1 主体已满足，唯 U6/U7 两条生命周期缺陷。

## 4. 崩溃与错误根因

- **v33 任务页 NPE 已修复**（v34 已发版）：TaskHubViewModel 属性声明在 init 之后 + Main.immediate 同步 launch；同类雷 SessionListViewModel 已一并排掉。
- **真机红条 `unexpected value for WorkTaskStatus enum: archived` 是服务端 500，不是 APP bug**：dell/win 两台的 codeg-server 程序版本比各自数据库旧（库里有 status='archived' 行，老服务端 Rust 枚举不识别，work_task_list 直接 500）。APP 已正确按设备隔离展示失败横幅。**根治要服务端版本对齐（动生产，需主人单独授权），APP 侧只能做展示分级**（见 U8）。上游最新源码枚举（db/entities/work_task.rs:17-47）也是 10 状态无 archived，archived 是独立布尔列 archived_at——说明 dell/win 数据库被更新版本写过后回滚，或跑过未知构建。
- 服务端最新状态机（核实自源码，作为 UI 映射唯一依据）：`todo, queued, preparing, running, awaiting_input, review, merging, done, failed, canceled`；archived/deferred 是覆盖列不是状态。

## 5. 可复用组件

- `WheelColumn` 滚轮选择器（feature/common/WheelPicker.kt，v34 新增，两 sheet 已用）。
- GlobalDeviceBar、FailedBanner（按设备聚合+重试）、ConfirmAction 确认弹窗模式、TaskDetailBody（changedFiles/events/diff，TaskHubScreen.kt:499-576）、InlineError/EmptyState/LoadingView（core/designsystem/component/）、PullToRefreshBox。
- CodegClient 端点模式：`POST {base}/api/<snake_case>`，请求 camelCase / 响应 snake_case，唯 `work_task_create` 的 `draft` 嵌套是 snake_case（CodegClient.kt:716-732 踩坑注释）。`git_list_all_branches` 客户端**已存在**（:417-418）；缺 return/deliver_pr/archive/delete/settings_effective 四个封装，defer 已有。
- DispatchTargetLoader：按设备动态拉文件夹（list_all_folder_details，含 path/gitBranch/defaultAgentType 字段，Folder.kt:16-28）+ acp_list_agents（按设备、可用性过滤）——§6.2"agent 动态加载"地基已在。

## 6. 需要修改的文件（按阶段）

- 阶段1（稳定性）：core/model/WorkTask.kt（KNOWN_STATUSES 补 archived/cancelled）、core/data/TaskBoard.kt（archived 归档隐藏语义）、app/src/test/.../TaskBoardTest.kt 等。
- 阶段2（导航与设备统一）：TaskHubScreen.kt（删 DeviceFilterRow）、TaskHubViewModel.kt（deviceFilter 改由 ServerRepository.selectedId+activityAll 驱动）。
- 阶段3（任务板）：TaskBoard.kt（四段映射）、TaskHubScreen.kt（工具栏：文件夹/搜索/筛选 + 四段 Segmented + 紧凑卡片 + 详情 BottomSheet）、TaskHubViewModel.kt、CodegClient.kt + WorkTaskWire（新增 return/deliver_pr/archive/delete）、strings.xml×2。
- 阶段4（派发）：InboxScreen.kt + HandoffSheet.kt（表单重排：设备→Agent→描述→项目→分支）、InboxViewModel/HandoffViewModel、DispatchTargets.kt（settings_effective 预填）、DispatchPipeline.kt（base_branch 接线）、CodegClient.kt。
- 阶段5（想法）：InboxScreen.kt（轻量卡片+右上角菜单+确认后移除）、InboxViewModel.kt、IdeaStore.kt（如需）。
- 阶段6（视觉）：CodegBackground.kt、Color.kt/Theme、统一卡片圆角间距、状态 Tag 色板。

## 7. 风险与兼容性

- 任务板从六列改四段是**纯展示层映射**（rawStatus 保留原始字符串），不建第二套状态机；未知状态放"等你处理"段内独立"未知状态"小节，中性标签+不提供状态类操作（只可看详情），绝不归为完成。
- 删任务（work_task_delete）和退回（work_task_return）是破坏性/写操作，一律走确认弹窗；所有操作以服务端真实端点为准，不伪造。
- 分支选择依赖 `git_list_all_branches`（需 folder path，FolderDetail.path 已有）；上游分支列表失败时降级为"跟随 HEAD"默认项并给短错误+重试，不阻塞建任务。
- settings_effective 预填仅作默认值，用户手动选择优先；接口失败静默跳过（提示"无默认 agent，当前选择仅用于本任务"）。
- 版本策略：本轮**不打 tag 不发 Release**（任务书 §13），只提交+推 fork main 跑 CI Verify；版本号待主人验收后再 bump。
- dell/win 服务端版本错位：APP 外问题，单独汇报待授权。

## 8. 阶段映射（任务书 §9 → 本仓实施）

任务书阶段 1→6 与上方 §6 文件清单一一对应；每阶段完成跑 `./gradlew :app:testModernDebugUnitTest :app:testLegacyDebugUnitTest`，阶段 3/4/6 追加 `assembleRelease`（modern+legacy）编译验证；阶段间分 commit 提交；最终产出 `docs/mobile-hub/UX-REFACTOR-REPORT.md` + 双子代理对抗审查（A：代码审查；B：异常场景/墨菲定律）。
