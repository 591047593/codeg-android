# UIUX-IMPLEMENTATION — Mobile Hub V2 实施记录（批v150 · ui重构任务书 交付物 2）

> 日期：2026-11-02 · 版本：v1.0.1-fengx.41（versionCode 43）· 基线 tag v1.0.1-fengx.40（commit 67a9610）→ 11 提交（77a22b7…848251f），33 文件 +3131/−757。
> 任务书：`/mnt/Projects/codeg/docs/writing-block (1).md`；验收清单：`writing-block (2).md`。

## 1. 新的信息架构

- 底部导航从五栏（动态|会话|文件夹|搜索|设置）改为**四栏：动态|收件箱|任务板|设置**。会话/文件夹/搜索降级为**推入面**：路由字符串全部保留（ROUTE_CHATS/ROUTE_FOLDERS/ROUTE_SEARCH 不变），从动态工作台与悬浮球可达；barBearingRoutes = tabRoutes + {chats,folders,search}，列表路由条 = tabRoutes + ROUTE_CHATS。
- 动态页升级为**工作台**：固定头部（设备统计行 + 快捷入口 + 需要处理预览）+ 原 feed 原样保留（ActivityScreen 未动 feed 逻辑）；数据源 WorkbenchViewModel（60s 只读轮询，gated by tab active + app foreground）。
- 收件箱/任务板晋升为独立 tab：InboxViewModel（含派发账本/探针/UNKNOWN 机制，收件箱专属）与 TaskHubViewModel（25s 看板轮询）各自 hiltViewModel() 挂导航条目；两 VM 轮询互斥（各自 active 闸门由 DisposableEffect 设置，tab 互斥渲染）。

## 2. 悬浮球改造

- 单击=开面板（保留原 1s 长按拖动语义，规划否决"长按=配置"以保肌肉记忆）；面板宽 230→304dp。
- 面板结构：**2×2 快捷卡**（新建会话/记录想法/创建任务/会话交接）+ **全局搜索行** + 分隔线 + **全部动作**折叠区（原 21 动作全量可达）+ 关闭行。
- 新增 4 个可绑定动作 id：quickIdea/quickTask/quickHandoff/quickSearch（`FloatingBallCatalog.ACTIONS` 追加；**默认绑定零变更**——目录追加即自动出现在设置页绑定选择器）。
- 面板所有动作仍走同一 onAction 注册表（MainShell），不绕开既有动作分发。
- 快捷交接链路：RecentSessionPickerSheet（各设备 listConversations 真实聚合，设备失败容忍、聚合超时同 AGG_DEVICE_TIMEOUT_MS 家族）→ handoffTo（single-flight）→ openConversationAndMark(replaceTop=true) → `HandoffAutoOpenBus.fire`（无进程内总线先例的既有 Bus 模式）→ 会话详情页按 `serverId:conversationId` key 消费（consume-on-match，防跨设备 id 撞车），自动展开 HandoffSheet。

## 3. 统一选择器（滚轮退役）

新组件 `core/designsystem/component/Pickers.kt`：`PickerField`（图标+标签+当前值+chevron）、`OptionListSheet`（>6 项自带搜索框、路径作 note、高亮当前值、380dp 高度上限）、`DeviceChipRow`（加载转圈/错误点来自 loader 预检真实数据，**不做假在线点**——ServerProfile 无在线字段）、`AgentChipRow`（首枚"自动"=不覆盖）。

替换三处：收件箱派发单（D2）、交接单三列滚轮（D3）、composer 两处文件夹 DropdownMenu（D3，可搜索+路径 note）。`WheelColumn/WheelPicker` 组件保留（WheelPickerIndexTest 几何契约单测继续生效），UI 调用点清零。

## 4. 派发表单双宿主契约（本批核心抽象）

`feature/dispatch/DispatchForm.kt`：`interface DispatchFormHost { targets / formUi / settings / loadTarget / loadBranches / dispatch(submit, onDone) }`，一份表单两个宿主：

- **宿主 A = InboxViewModel**（收件箱派发单）：账本/探针/UNKNOWN 三闸/陈旧目录检查全部留在收件箱侧；formUi 由既有 ui state 投影（`ui.map { … }`），dispatch 走适配 overload（ideaId 回查草稿）。
- **宿主 B = QuickActionViewModel**（悬浮球创建任务/会话）：薄守卫管线（dispatching 互斥 → 标题非空 → hasUnresolvedDuplicate 双创建闸 → 定时过期归零 → 陈旧目录检查）→ DispatchService.send；结果 UNKNOWN 归全局账本，从收件箱卡片处置——不在快捷入口造第二套状态机（任务书 §十四.4）。
- **v146 不变量随契约迁移**：rememberSaveable id、30s 时钟 tick 过期提示、branchFresh 以 `serverId:folderPath` 键控、进程死亡恢复 LaunchedEffect、agent/目录与已加载列表校验、settings 派发默认预种、canDispatch 闸、结果对话框屏级渲染、sheet 拖拽锁（v148）每宿主 sheet 各挂一份。
- 字段顺序按任务书 §6.1：执行方式 → 设备 chips → Agent chips（自动优先）→ 项目 PickerField/OptionListSheet → 分支（仅任务，默认行 `__default__`）→ 标题 → 描述 → 定时（仅任务）→ 派发摘要卡 → 主按钮（创建会话/创建任务）。

## 5. 修改的主要文件（按阶段/提交）

| 阶段 | 提交 | 内容 |
|---|---|---|
| A | 77a22b7 | 四栏导航地基 + P0 会话模式首条指令发射端接线 |
| B | 87dd237 + fb90dfa | 动态工作台（统计/快捷入口/需要处理预览）+ VM 折叠修复 |
| C+D1 | f40845f | 悬浮球快捷中心 + Pickers.kt 四组件 + DispatchForm/宿主B + QuickIdeaSheet + RecentSessionPickerSheet + HandoffAutoOpenBus + 28 条双语文案 |
| D2 | 6f01ed6 | 收件箱派发表单切 DispatchForm（宿主 A），610 行滚轮表单退役 |
| D3 | d938ca3 | 交接单三滚轮 + composer 两处文件夹菜单 → 统一选择器 |
| E | d3255d5 | ServerEditorScreen 绿色 token 化 + 版本号 41/43 + VM 作用域复核 |

新增文件：`feature/dispatch/DispatchForm.kt`、`feature/quick/{QuickActionViewModel,QuickIdeaSheet,QuickTaskSheet,RecentSessionsViewModel,RecentSessionPickerSheet}.kt`、`feature/handoff/HandoffAutoOpenBus.kt`、`feature/activity/WorkbenchViewModel.kt`、`core/designsystem/component/Pickers.kt`。

## 6. 能力保留清单（任务书 §四.7「原有功能没有丢失」）

会话列表/详情/发送/上传、文件夹、全局搜索、想法收件箱全生命周期（记录/转派发/删除）、派发账本三态与探针自愈、重派发、会话模式/任务模式派发、定时派发、分支选择（local+remote 全量）、交接 A/B 模式、悬浮球 16 手势×21+4 动作绑定、派发默认设置、项目过滤——全部保留；无一路由删除，无一 ViewModel 删字段。

## 7. 规划内偏差（如实记录）

- **DispatchForm 提前到 C 提交落地**：快捷任务卡必须打开真实表单（不做假按钮），收件箱 D2 随即复用同一契约，不变量只做一遍。
- **工作台统计为固定头部**而非 feed 内插卡：滚动位置稳定，feed 代码零改动。
- **需要处理预览放 feed 之上**：任务书 ui2 对照中该区在最上方，与"工作台先答急事"的定位一致。
- **滚轮组件保留不删**：单测几何契约仍锁行为，防未来复用再犯 v149.1 错位。
- 悬浮球**保留 1s 长按拖动**（规划明确否决"长按=配置"改法，单击开面板已覆盖配置可达性）。
- **默认强调色橙→青蓝**：`AccentPalette.DEFAULT` 与持久化层 `AppSettings.DEFAULT_ACCENT` 同步改为 teal（两处必须一致，否则新用户仍拿 orange、DEFAULT 成死代码）。从未显式选过配色的用户升级后外观会变；显式选过 orange 的用户存了 id，保持不变。
- **想法卡时间沿用 `updatedAt`**：收件箱卡显示最后编辑时间（原卡既有行为），未改成 `createdAt`。
- **已知可及性缺口**：设备 chips 的「预检失败」状态目前只有 6dp 红点（装饰性、无 contentDescription），色觉障碍用户只能从加载结束后仍无 Agent/项目列表间接察觉；Todo 记入后续批（需配合文案/语义方案，不在本批临时加假提示）。
- **审查期修复不改变架构**：10 轮审查的修复集中在 import 完整性、默认值一致性、异步事件时序（保存/派发迟到回调）与工作台刷新时机，DispatchFormHost 双宿主契约、路由与数据层零改动。
