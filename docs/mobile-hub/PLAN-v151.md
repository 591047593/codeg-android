# 批v151 施工图 — 工作台折叠 / 二级屏重构+手势 / 弹层误关闭 / 想法按钮样式 / 悬浮球扩充 / 全局新建会话

> 日期：2026-11-02 · 基线：tag v1.0.1-fengx.41（versionCode 43，8d54671）· 目标版本：v1.0.1-fengx.42（versionCode 44）
> 需求源：主人 2026-11-02 十条口述（工作台折叠、二级屏 1:1 打磨、弹层下滑误关、三屏手势切换+搜索全部、想法 FAB 样式、悬浮球动作扩充、全局新建会话）+ 至少 30 轮全 App 第一性原理审查 + GH Actions 发版推 TG。
> 红线继承：不动 Rust 服务端/不造状态机/不加中央库；真实数据无假 UI；默认绑定零变更；secret 不进 git；本地不编译。

## 0. 摸排结论（实施依据）

- 工作台 = `Column{ WorkbenchHeader(固定) ; LazyColumn(feed) }`（ActivityScreen.kt:193-308），头部三块：StatsRow / QuickRow（WorkbenchWidgets.kt:73-157）/ AttentionCard（:213-267，attentionTasks==0 整卡早退 :219）。折叠头部=直接让位给 feed。仓内折叠惯用法：`CollapsibleSectionHeader`（SectionHeader.kt:48-115）+ `AnimatedVisibility(expandVertically(tween(220)))`。
- 设备条是唯一共享组件 `GlobalDeviceBar`（GlobalDeviceBar.kt:85），由 MainShell 在 barBearingRoutes（tab×4 + chats/folders/search，MainShell.kt:242-244）统一渲染；`showAll` 仅 Activity+chats 为 true（:791）。全部开关是 ServerRepository 内存态 `_activityAll/_chatsAll`（ServerRepository.kt:80-96）。聚合机制参照 `SessionListViewModel.fetchAggregate`（:294-434，渐进 fan-out + DeviceLoad 状态）。
- 手势：`TabSwipeGesture.tabSwipeNavigation`（TabSwipeGesture.kt:30-90）+ `resolveDeviceSwipeStep`（:175-189，含 WITH_ALL 域、单测 ResolveDeviceSwipeStepTest）已在动态页用于"设备内切换优先、边缘切 Tab"。全 App 无 Pager。
- 弹层误关根因：`SheetGestures.kt:30 onPostFling` 把向下剩余速度原样放行（v148 只闸了 onPostScroll）→ 列表在顶部时下甩即关。含滚动内容的弹层共 15+（QuickIdeaSheet / QuickTaskSheet(DispatchForm) / RecentSessionPickerSheet / HandoffSheet / 收件箱 DispatchSheet / TaskDetailSheet / OptionListSheet×5 调用点 / AgentOptionsSheet 两页 / ComposeInsertSheet / MessageTocSheet / SessionDetailsSheet / PlanProgressSheet / AddToProjectSheet / ProjectFilterSheet / BranchPickerSheet 等）。
- 派发链路：DispatchForm 天然支持设备后选（device 初始 null，DispatchForm.kt:158；canDispatch 闸 :475-477）；QuickActionViewModel 是现成宿主 B；QuickTaskSheet 已有 defaultSessionMode 参数（:62-71）。会话模式确认后自动跳转的样板 = InboxScreen.kt:233-241（handoffTo + openConversationAndMark）。QuickTaskSheet 目前 onResult 只出回执不跳转（:65-70）——补跳转回调。
- 想法 FAB：InboxScreen.kt:123-129 默认 M3 FAB（注意：它开的是 IdeaEditorDialog，不是 QuickIdeaSheet；QuickIdeaSheet 仅悬浮球路径 MainShell.kt:1095）。设置项样板：AppSettingsStore ACCENT 键（:222）→ AppSettings 字段 → GeneralContent chip 行（GeneralScreen.kt:94-125）。
- 悬浮球：25 动作 / 16 手势（FloatingBallActions.kt:25-54）；缺 tabInbox、tabTasks、quickNewSession、toggleAllDevices、refreshActivity。ids 一旦持久化不可改名（sanitize :87-94 丢未知 id）。

## P1 工作台折叠/展开（需求1）

- 两个独立折叠区，状态持久化（新 `WorkbenchDisplayStore`，DataStore 两布尔 `overviewCollapsed`/`attentionCollapsed`，默认 false=展开）：
  - **区A 总览+快捷**：头部最上新增 32dp 细轨：展开时右端 28dp 收起圆钮（KeyboardArrowUp，contentDescription=a11y）；点击收起 → 细轨显示单行摘要「N 运行中 · N 需要处理 · N/M 在线」+ 下箭头，StatsRow+QuickRow 包 `AnimatedVisibility(expandVertically(tween(220))+fadeIn)` 收起。点击细轨任意处展开。
  - **区B 需要处理卡**：标题行尾加 24dp chevron 切换；收起时卡内预览行（AttentionRow×N + 还有 N 项）收起，仅剩标题行 + 副标题「N 项待处理」。
- 不做滚动自动收纳（predictability 优先，写进偏差记录）。
- 摘要行复用 stats 数据源（WorkbenchViewModel.stats，无新轮询）。a11y：轨/卡行 Role.Button + expand/collapse 文案。

## P2 三屏重构 + 手势切换 + 搜索「全部」（需求2/3）

- **GlobalDeviceBar 1:1 打磨**（一次改动三屏全生效）：chip 高度 ≥40dp 视觉/48dp 触达（加 minHeight+padding），文字 15sp，选中态 accent 容器+白字+对勾缀，未选中态加描边；chip 间距 8dp；条本体横向可滚动不变；<2 设备隐藏逻辑、聚合<2 强制退出守卫、长按 sheet 原样保留。
- **FolderRow 打磨**（ProjectListScreen.kt:213-244）：行 min 56dp、图标砖 44dp、名字 16sp/medium、路径 12sp mono ellipsis middle、次要信息统一 onSurfaceVariant、chevron 20dp 次级、分隔线缩进对齐文字。
- **手势切设备**：chats/folders/search 三屏内容包 `tabSwipeNavigation`（复用动态页机制）：chats=WITH_ALL（全部+dell/tx/…），search=WITH_ALL（新增全部后），folders=仅设备循环（无聚合数据，不加全部——写偏差）。滑动经 `resolveDeviceSwipeStep` 纯函数，边缘行为与动态页一致（listRoutes 语义不变）。单测补三屏 scope 解析用例。
- **搜索「全部」**：ServerRepository 加 `_searchAll` StateFlow + AppViewModel 透传；MainShell barBearingRoutes 对 ROUTE_SEARCH showAll=true、allSelected=searchAll；All 点击=直接切换（无项目过滤二级面板，与 chats 区分，记录偏差）。SearchViewModel 加聚合模式：可见且 includeInAll 的设备 fan-out `listConversations(search=q, sortBy="updated")`，单设备 10s 超时、失败隔离（DeviceLoad 状态），结果按 updatedAt 合并去重（serverId:conversationId 键）+ 设备名角标；每设备拉取上限沿用 AGG_FETCH_LIMIT(200)；300ms 防抖不变；失败横幅复用聚合文案。单测：合并/去重/超时纯函数。
- **【方案审查修正·数据安全】搜索全部模式的行操作**：聚合结果行携带 owning serverId；点开走 owning-device 切换（`handoffTo`/`openConversationAndMark`，MainShell :869-879 聚合会话同款）；**pin/改名/删除在全部模式禁用**（行菜单隐藏，只留打开），`folderNames` 全部模式下按设备分别解析或显示设备名角标替代——绝不允许把 B 设备的会话 id 送到 A 设备的 client 上执行。
- **【方案审查修正·不变量接线】**：`exitAggregate()`（AppViewModel.kt:278-282）必须同时清 `_searchAll`；MainShell `onSelectServer` 守卫（:795-804）把 searchAll 纳入条件（否则全部模式下点已选 chip 无退出路径）；`onSelectAll`（:812-819）现 `else` 分支默认 ACTIVITY——必须为 ROUTE_SEARCH 插入专用分支（纯切换、不开项目 sheet），<2 设备强退走 exitAggregate。
- **【方案审查修正·手势边界】**：chats/folders/search 三屏 `resolveDeviceSwipeStep` 返回 Tab 判定时一律 no-op（推入屏没有外层 tab，禁止复用 Activity 页 currentTabIndex 算术，否则末设备再滑会跳到底部 tab）；folders 无聚合数据 → swipe 域=DEVICE_ONLY（仅设备循环，不加「全部」，记录偏差）；`resolveDeviceSwipeStep` 扩 scope 参数（WITH_ALL/DEVICE_ONLY）+ 每路由 all-flag 索引源（chatsAll/searchAll/activityAll）；三屏各补解析单测。

## P3 弹层下滑误关闭修复（需求2 附加，全 App 排查）

- 根因修复在 `SheetGestures.kt`：新增 `Modifier.sheetContentScrollLock()` = NestedScrollConnection，契约（【方案审查修正】）为：**onPreScroll/onPreFling 一律放行（返回 Zero，绝不抢内容滚动与动量）；onPostScroll/onPostFling 无条件消费全部垂直剩余（向上向下都吃）**——列表在顶部下甩不再误关，上滑滚动也不再拖动卡片，普通滚动手感零变化。真值表单测按「方向 × 阶段(pre/post) × 类型(scroll/fling)」三维。
- 关闭途径：固定头部/标题区拖拽（标题不在任何滚动容器内，手势直达 M3 sheet，原生接手）+ 点遮罩 + 系统返回。实施后逐弹层核对「拖固定头可关」；头部被卷进滚动的弹层重构为「固定头 + 滚动体」：QuickIdeaSheet（标题固定）、TaskDetailSheet（标题固定）、**HandoffSheet（标题从 verticalScroll Column 提出-reviewer 指认）**、**收件箱 DispatchSheet（本无头部，加细头含标题）**、SessionDetailsSheet / AgentOptionsSheet 两页 / AddToProjectSheet / ProjectFilterSheet（标题提出滚动区）。dragHandle=null 样式保留；头部拖拽即关闭通道，不额外加把手。
- 落点（全部含滚动内容的弹层，含 drag-locked 与未加锁两组）：
  1. 共享组件一处：`OptionListSheet`（Pickers.kt:172 LazyColumn）→ 5 个调用点自动生效（含目标项目弹层=主人截图场景）。
  2. 逐点加 `sheetContentScrollLock`：QuickIdeaSheet、DispatchForm 主体滚动（收件箱+快捷任务共用）、RecentSessionPickerSheet、HandoffSheet、TaskDetailSheet、AgentOptionsSheet 主页+分支页、ComposeInsertSheet(InsertList)、MessageTocSheet、SessionDetailsSheet、PlanProgressSheet、AddToProjectSheet、ProjectFilterSheet、BranchPickerSheet。
  3. 头部被卷进滚动的弹层重构为「固定头 + 滚动体」：QuickIdeaSheet（标题固定）、TaskDetailSheet（标题固定）；其余本就分层。
- `rememberSheetDragLock` 保留在既有挂点（根级）作为第二道闸；新增锁只更深不改浅。
- 纯逻辑单测：新增 `SheetScrollLockDecisionTest` 锁决策函数（方向×fling/scroll 组合真值表）。

## P4 新建想法按钮样式（需求4）

- AppSettingsStore 新键 `inbox_new_idea_style`（"corner"默认 / "centerWide"），AppSettings 字段+setter，合法值常量；GeneralScreen 通用区加 chip 行「新建想法按钮」两选项（说明文案：底部居中半屏宽，左右手都好点）。
- InboxScreen：style==corner → 现 FAB 原样；centerWide → FAB 槽置 null，Scaffold body 底部居中渲染宽按钮：宽=屏宽 50%（BoxWithConstraints），高 56dp 同 FAB，圆角 16dp、accent 容器、Icon(Add)+「新想法」、底 padding 与 FAB 一致（含导航栏 inset），触达 ≥48dp；列表 contentPadding bottom 两模式统一 96dp+inset。
- 两个样式都开 IdeaEditorDialog（不改变现状：FAB 开编辑框而非 QuickIdeaSheet——偏差记录：QuickIdeaSheet 走悬浮球，两入口并存）。

## P5 悬浮球动作扩充（需求4.1）

- 新增 5 个可绑定动作（ids 永久稳定，默认绑定零变更；测试计数 25→30）：
  - `tabInbox` 收件箱、`tabTasks` 任务板（补齐四 tab 直达；MainShell onTab）
  - `quickNewSession` 全局新建会话（P6 入口，开新 sheet）
  - `toggleAllDevices` 切换全部/单设备（仅作用于 activity/chats/search 三类列表路由；其余路由 no-op——与 cycleProject else 分支同款）
  - `refreshActivity` 刷新动态（MainShell 无 ActivityViewModel 句柄——走 `appViewModel.refreshActivityAll()` → `ServerRepository.requestActivityRefresh()` tick，ActivityViewModel 两种模式都已 combine 该 tick，原地刷新）
- 面板 2×2 快捷卡不变（newSession/quickIdea/quickTask/quickHandoff）；新动作进「全部动作」折叠区与设置绑定列表。
- 需求4 的「新建想法挂悬浮球手势」v150 已有（quickIdea 可绑 16 手势），本批在汇报中指路。

## P6 全局新建会话（需求5）

- 复用宿主 B：`QuickNewSessionSheet` = QuickTaskSheet 同构（复用 DispatchForm），defaultSessionMode=true、标题「新建会话」、按钮「创建会话」；设备 chips 列全部可见设备，选中后 loadTarget 拉该设备目录（既有 seq-guard 管线），目录→Agent→描述→创建。
- MainShell：`showQuickNewSession` 状态 + 独立 AtomicInteger open token（复制 :261-269 模式）。
- 成功自动跳转：onResult 若 `ref.sessionMode && CONFIRMED && remoteTodoId!=null` → **跳过 shell 级回执对话框**（QuickTaskSheet 的 confirmCreated 不调用，避免「跳转+回执」双重 UI）→ `handoffTo(serverId)` → `openConversationAndMark(..., replaceTop=true)`（镜像 InboxScreen :233-241）；UNKNOWN/失败不出跳转、只出回执/账本（不造假成功）。
- 会话模式首条指令经既有 SessionAutoStart（DispatchService :203-208 arm）自动发送——零新机制。
- 工作台「新建会话」按钮与悬浮球 `newSession` 行为不变（设备上下文 composer）；全局入口=悬浮球 quickNewSession（+面板暂不加卡，保持 2×2）。

## 交付与验收

- 版本 1.0.1-fengx.42 / versionCode 44；CI：push→Verify→tag v1.0.1-fengx.42→Release 双 APK+sha256+TG。
- 单测新增：resolveDeviceSwipeStep 三屏用例、搜索聚合合并/去重纯函数、SheetScrollLockDecision 真值表、FloatingBallCatalog 25→30。
- 审查流水：方案子代理审查 → 分期实施 → 批内对抗审查（编译面/契约/墨菲/红线）→ **≥30 轮全 App 第一性原理多维审查**（并行分组：导航与状态恢复 / 派发与数据完整性 / 触达与可及性 / 并发生命周期 / 文案与双语对账 / 性能资源；每轮独立子代理，发现即修）→ 发版。
- 已知偏差（规划内）：folders 不加「全部」（无聚合数据源）；搜索「全部」无项目过滤二级面板且 pin/改名/删除禁用；工作台不做滚动自动收纳；收件箱 FAB 两入口（编辑框 vs 悬浮球 QuickIdeaSheet）并存；P1 折叠态经 DataStore 异步加载，冷启首帧可能闪一帧展开态（接受并记录）。
- 基线备注：tag .41=8d54671，分支 HEAD=229f7bf（批v150 收尾 docs，同为 versionCode 43）；v42 从 HEAD 发。

## 附录：主人十条口述（验收对照）

1. 动态页上两区折叠/展开，给最近 24 小时让位（P1）
2. 图二（文件夹/设备选择屏）1:1 打磨重构（P2 GlobalDeviceBar+FolderRow）
3. 弹层内下滑查看顶部误触发关闭——全 App 二级卡片排查修复（P3）
4. 会话/项目/搜索三屏左右滑快速切换设备；搜索顶部加「全部」（P2）
5. 想法新建按钮加大 + 设置加样式切换（右下小卡默认 / 底部居中半屏宽）（P4）
6. 新建想法挂悬浮球手势（v150 quickIdea 已有，本批汇报指路）
7. 悬浮球手势指令扩充：盘点全 App 高频操作补可绑定动作（P5）
8. 全局新建会话：不预绑设备，选设备→目录联动→创建（P6）
9. 完成后子代理对抗审查 + 墨菲验收（P9）
10. ≥30 轮全 App 第一性原理多维审查迭代（P10）；GH Actions 发版推 TG（P11）
