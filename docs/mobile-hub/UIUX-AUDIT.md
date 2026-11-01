# UIUX-AUDIT — Mobile Hub V2 重建审计（批v150 · ui重构任务书 交付物 1）

> 审计日期：2026-11-02 · 基线：v1.0.1-fengx.40（versionCode 42，commit bf43de0）
> 依据：`/mnt/Projects/codeg/docs/writing-block (1).md`（UI/UX 重建任务书）+ `writing-block (2).md`（验收清单）+ ui1/ui2 对照图。
> 结论口径：全部基于源码阅读与既有真机反馈记录；dell 无模拟器/真机环境且本地编译被资源纪律禁止，未截新图、不做假图。
> 前置：阶段 0 的 V1 基线审计见 `docs/mobile-hub/UX-AUDIT.md`（2026-11-01）；本文件是重建批（v150）的差距审计与实施期复核。

## 1. 任务书要求 → 基线差距总表

| # | 任务书条款 | 基线（v40）状态 | 差距定性 |
|---|---|---|---|
| A1 | 底部四栏 动态\|收件箱\|任务板\|设置（§2.1） | 五栏：动态\|会话\|文件夹\|搜索\|设置；收件箱/任务板藏在动态二级页 | 结构性缺失 |
| A2 | 动态=工作台，一眼答"所有设备在做什么"（§2.3/§四.4） | 动态只有原始 feed + 三子页切换，无统计、无需要处理摘要 | 缺失 |
| A3 | 任何页面 ≤2 步记想法/建会话（§四.1-2） | 想法：动态→想法子页→输入（3 步）；建会话：动态→会话→FAB（依赖所在 tab） | 路径过深 |
| A4 | 不进收件箱也能建任务（§四.3） | 建任务只能从动态→想法子页派发单 | 结构性缺失 |
| A5 | 悬浮球=快捷动作中心（§3） | 悬浮球仅单动作绑定，无面板、无搜索入口、无全部动作汇总 | 缺失 |
| A6 | 统一选择器 chips+BottomSheet，禁滚轮（§6.1） | 派发单/交接单三列 WheelColumn 滚轮；composer 文件夹为锚定 DropdownMenu（无搜索） | 交互违约 |
| A7 | 视觉 token 统一（§7） | ServerEditorScreen 连接成功绿为硬编码 `Color(0xFF85D18F)` | 违约一处 |

## 2. 关键证据（文件:行号为基线 v40）

- 五栏导航与 barBearing：`feature/main/MainShell.kt`（tabRoutes 含 chats/folders/search）；收件箱/任务板页内子页切换：`feature/activity/ActivityScreen.kt:100`（pageMode 0/1/2）。
- 滚轮选择器：`feature/inbox/InboxScreen.kt` 旧 DispatchSheet（559-1170 行，三列 WheelColumn）+ `feature/handoff/HandoffSheet.kt:221-281`（同款三列）；滚轮几何风险已被 v149.1 真实事故证明（WheelPicker 行号错位/末行吞提交，见 `docs/mobile-hub/EXECUTION-LOG.md` 批v149.1）。
- composer 文件夹菜单：`feature/sessiondetail/SessionDetailScreen.kt:1180` 与 `feature/sessiondetail/AgentOptionsSheet.kt:438`（DropdownMenu，无搜索、屏幕边缘遮挡）。
- 建任务路径：动态页 FAB → 想法输入 → 派发单；无独立任务创建入口。
- 悬浮球：`feature/floatingball/FloatingBall.kt`（单绑定 onAction，无面板）；绑定目录 `FloatingBallCatalog`（16 手势 × 21 动作）。
- 硬编码绿：`feature/server/ServerEditorScreen.kt:99`。

## 3. 约束复核（任务书 §十四）

逐条确认不违反：不新增中央服务器/数据库、不改 Rust 服务端核心、不自建第二套状态机/调度器/队列、不绕开原生 API（派发/交接/想法/会话全部走既有 DispatchService、HandoffViewModel、IdeaStore、CodegClient）、不破坏多服务器配置（ServerProfile/AppSettings 数据结构零变更）、不因 UI 改造删除功能（§5 能力清单逐项保留，见 UIUX-IMPLEMENTATION.md §6）。

## 4. 实施期新增发现（重建过程中确认，均已在 v150 修复）

- 旧收件箱派发表单为 610 行独立实现，与派发语义强耦合且三处（派发单/快捷任务/未来入口）必然漂移 → 引入 `DispatchFormHost` 双宿主契约收敛（宿主 A=InboxViewModel，宿主 B=QuickActionViewModel）。
- WorkbenchViewModel 首版 onFailure 分支有花括号错位（87dd237 提交破损）→ fb90dfa 修复并引入剥离源码括号平衡静态闸。
- `ServerProfile` 无在线字段，设备 chips 不做假在线点——加载/错误状态来自 loader 预检真实数据（errorDeviceIds/loadingDeviceId）。
