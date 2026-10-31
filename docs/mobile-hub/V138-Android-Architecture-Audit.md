# V138 Android 架构审计

> 审计对象：fork `android/` @ fbf2491（v137，1.0.1-fengx.27）。239 个 Kotlin 文件，单 Activity + Compose + Navigation Compose + Hilt。

## 架构总览
- 入口 `MainActivity.kt`（@AndroidEntryPoint，`codeg://` DeepLink → `DeepLinkBus`）→ `app/CodegApp.kt` → `feature/main/MainShell.kt`（1155 行：NavHost + 5 tab 外壳，`HomeTab` 枚举 CHATS/FOLDERS/ACTIVITY/SEARCH/SETTINGS 可排序可设起始页）。
- 包结构：`core/{common,crash,data,datastore,designsystem,model,network,security}` + `feature/{activity,floatingball,main,onboarding,projects,search,server,sessiondetail,sessions,settings}`。
- 无独立 route 表文件；路由字符串在 MainShell。

## 网络层（v139-v142 直接复用）
- `di/NetworkModule.kt:30`：单例 Ktor HttpClient(OkHttp)，30s 超时（配在 OkHttp 引擎层）。
- `core/network/CodegClient.kt:185`：所有端点 POST /api/*，`forkSession`(:342) 已封装。`CodegClientFactory.kt:23` 按服务器懒建 client/eventStream。
- `core/network/EventStream.kt:39`：/ws/events + subprotocol 鉴权；`WsFrames.kt` 帧解析（Ready/Snapshot/Replay/Event/Detached/Pong/Closed）。无全局自动重连，重连在 `SessionDetailViewModel.kt:129`（backoff）。
- 错误模型 `ApiError.kt` / `ErrorMessages.kt`。

## 数据层
- **无 Room**，全部 DataStore Preferences（`di/StorageModule.kt`）。`ServerStore.kt`：profiles JSON 存单 key。
- Token：`core/security/KeystoreSecretStore.kt:24`（AndroidKeyStore AES-256-GCM，密文 base64 存私有 prefs；接口 `SecretStore` 只有 put/get/remove）。
- `ServerProfile`（core/datastore/ServerProfile.kt）：`id/name/baseUrl/visible/includeInAll`（v137 新增后者）。

## 多设备聚合（v139 直接复用的核心资产）
- `core/data/DeviceLoad.kt`：纯函数渐进加载（LOADING/OK/FAILED、AGG 3s/单设备 4s 超时、600ms 骨架、failedNames/retryableIds/aggregateDeviceSignature）。已有 DeviceLoadTest。
- `core/data/ServerRepository.kt:31`：visibleProfiles、setIncludeInAll、聚合开关 activityAll/chatsAll、跨页广播 conversationsChanged/foldersChanged。
- `ActivityViewModel.fetchAllDevices/retryFailedDevices`（:437/:565）与 `SessionListViewModel`（:249-315）同构轮转骨架。
- `GlobalDeviceBar.kt`：设备 chip 条（长按跳过）；错误横幅内联在 ActivityScreen/SessionListScreen（可提取共享）。

## 任务/审批现状
- **无任何 todo/board/task 封装**（"task" 全是 ROUTE_NEW_TASK 语义）→ v139-v140 全新。
- 可复用：`core/model/Interactive.kt`（PermissionOption/QuestionSpec/PlanApprovalDecision）、`AcpEvent.kt`（PermissionRequest/QuestionRequest/PlanApprovalRequest/StatusChanged）、三张审批卡片（feature/sessiondetail/interactive/）、`CodegClient.respondPermission/answerQuestion/answerPlanApproval`。
- pending 弹窗目前只在会话详情内；跨设备 pending 汇聚是新增面。

## 会话
- 模型：`Conversation.kt`、`Message.kt`（MessageTurn/ContentBlock）、`SessionSnapshot.kt`。
- `SessionDetailViewModel`：WS 订阅+重连、steeringAvailable（依赖服务端比本地快照新，见 Risk-Register）。
- `forkSession` 已封装（v134）。

## UI 可复用
Markdown 全套（core/designsystem/markdown/）、`DiffView.kt:41`、`CenteredActionMenu.kt`、`StateViews`/`Badges`/`Glass`。
设置页：`SettingsScreen.kt:78` 私有 `SettingsLeaf` 枚举 + 内部状态切换（v142 入口照此加）。

## 测试
- `app/src/test/`：53 个纯 JVM 测试。模式：core 层纯函数 + JUnit + coroutines-test。
- `RouteParityTest.kt`：路由对账守卫（125 路由 vs tools/_server_routes_snapshot.txt）——**新增端点封装必须同步更新快照**（tools/route-parity.sh）。

## 发布
- 版本：app/build.gradle.kts:41-42。CI：verify.yml（push main 跑 check+assemble）、release.yml（tag 触发，双 APK + TG 直链）。

## v139-v142 新增面
| 版本 | 复用 | 新增 |
|---|---|---|
| v139 | DeviceLoad 全套、ServerRepository、审批模型与卡片、DiffView、聚合 VM 骨架 | feature/taskhub/ 包（VM+Screen+四列看板）、CodegClient 补 work_task_* 端点、本地 todo 缓存 store、跨设备 pending 聚合 |
| v140 | DataStore store 模式（仿 DraftStore）、GlobalDeviceBar、CenteredActionMenu | IdeaStore + 收件箱页 + 派发向导 + request_id 记账 |
| v141 | forkSession、ConversationNav(owner serverId)、MessageTurn | 交接包模型/编辑器/预检（git_status 需 path，先取 folder 详情） |
| v142 | KeystoreSecretStore 基元、ServerStore upsert、Base64Url | 加密导出/导入模块 + SettingsLeaf 入口；SecretStore 需能导出 token 明文（仅内存中转） |
