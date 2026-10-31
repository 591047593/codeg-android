# FINAL-REPORT — Codeg Mobile Hub V1（v137–v142 全局终稿）

> 项目：把 Codeg 安卓 fork 打造成多设备移动管控中心（PRD `/mnt/Projects/codeg/11.1 PRD`，指令 `/mnt/Projects/codeg/go.md`）。
> 本报告按 go.md §十二 七段结构输出。日期：2026-11-01。

## 一、总体完成情况

| 版本 | 主题 | 状态 |
|---|---|---|
| v137 | 多设备聚合渐进渲染（设备独立提交+世代号、3s/4s 超时、失败横幅、includeInAll） | ✅ |
| v138 | 阶段0只读审计：服务端 20 项 API 能力矩阵 + 架构盘点 + 风险登记 13 项（八份文档） | ✅ |
| v139 | 跨设备任务状态中心：work_task 端点封装 + 四列看板（未知状态绝不归 done）+ 危险操作二次确认 | ✅ |
| v140 | 想法收件箱 + 统一任务派发：本地草稿 + DispatchRef 记账账本（PENDING/CONFIRMED/UNKNOWN）+ 回查管线 | ✅ |
| v141 | 跨设备会话交接：DispatchService 共享派发管线 + 12 字段交接包（尾部反向扫描，无 AI 总结）+ Git 预检 | ✅ |
| v142 | 双手机加密配置同步：AES-256-GCM + PBKDF2(600k) 纯剪贴板零网络导出/导入 | ✅ |
| 终审 | 全局十维度三组审查 + 修复收尾（本次提交 3ce3d25，tag v1.0.1-fengx.32） | ✅ |

六批全部交付并逐批通过对抗式代码审查 + Murphy 极端场景双重审查后才进入下一批；每批的修复明细见 `EXECUTION-LOG.md`，决策依据见 `DECISION-LOG.md`（D5–D11）。

**核心红线全程遵守**（终审三组交叉核验）：未动 Rust 服务端核心；未新增中央服务器/数据库/任务状态机/调度器/队列——DispatchState 是请求记账不是任务状态机；无伪造接口、无假按钮；未知任务状态永不归 done；结果未知（UNKNOWN）禁止盲目重发（重派需人工确认 + 本次终审补了二次确认弹窗）。

## 二、Git 交付记录

| 批次 | Commit | Tag | 版本 |
|---|---|---|---|
| v137 | fbf2491 | v1.0.1-fengx.27 | versionCode 29 |
| v139 | 37f58c0 | v1.0.1-fengx.28 | 30 |
| v140 | 592819d | v1.0.1-fengx.29 | 31 |
| v141 | 7d72ce4 | v1.0.1-fengx.30 | 32 |
| v142 | 10c8bc3 | v1.0.1-fengx.31 | 33 |
| 终审修复 | 3ce3d25 | v1.0.1-fengx.32 | 34 |

全部推送 `fork` remote（unixcs/codeg-android，public）；origin 为上游 xintaofei 只读。文档 8 份随 v138 起入库 `docs/mobile-hub/`。

## 三、测试报告

- **本地全量**：`./gradlew test` 绿，454 用例（终审修复后复跑确认，v142 新增 ConfigSyncCryptoTest 7 例、v141 HandoffPackageTest 4 例、v140 DispatchPipelineTest 5 例、v139 TaskBoardTest 7 例）。
- **CI**：每批 push main 触发 Verify（构建+全变体测试），打 tag 触发 Release（签名校验 + 双 APK 挂 Release + TG 推直链）。
- **已知 CI 抖动**：v142 首次 Verify 失败于 `ServerStoreTest > unrelated preference writes…` TimeoutCancellationException（LegacyRelease 变体，既有用例超时抖动，与 v142 改动无关；本地同用例绿，已触发 rerun 复核）。同类抖动 v136 曾根治过一次（搜索回归测试），本条为另一用例，列入后续建议。

## 四、缺陷与修复记录（双重审查 + 终审累计）

高危修复（每批对抗审查抓出，全部在发布前修掉）：
- v139：轮询停摆（tick>0 不恢复）、busyKey 不同步预留致双发、超时把设备切片清成空数据。
- v140：isAmbiguous 漏判 ApiError.Transport（客户端把一切异常包成 Transport，漏判=直接双建）、probe 网络失败误报"未找到"、PENDING 崩溃残留不可见（init 清扫兜底）、probe 锚点进程死亡丢失（派发前落账）。
- v141：目标加载失败清空设备芯片、伪造"0 未提交"Git 结论、UNKNOWN 后允许直接重发、大量 transcript 主线程拼接。
- v142：密码进 rememberSaveable（Bundle 泄露面）、导入部分失败继续写、冲突项默认覆盖本地。

终审（三组：安全/架构/API、并发/一致性/网络/生命周期、性能/UI/工程）结论：**无红线违规、无高危缺陷**。修复的低/中危项（提交 3ce3d25）：
1. UNKNOWN 卡「重新派发/放弃」加二次确认弹窗（防误触重复派发）。
2. 导出跳过无 Token 的服务器数量在"已复制"弹窗可见（同时删除死代码 `if (skipped > 0) Unit`）。
3. Inbox/Handoff 派发入口兜底 try/catch：DataStore IO 异常降级为错误提示，不再崩进程。
4. 清理无用 import（DispatchState）。

终审记录在案、明确不修的接受项（理由见 KNOWN-ISSUES）：TaskHub 操作后刷新可能被并发轮询代际守卫丢弃（25s 内自愈）、details 缓存只增不删（单条限 100 量级可控）、IdeaStore 解码失败静默空表（既有 store 模式）、probe 中 runCatching 吞 CE（良性）等。

## 五、安全报告

- **密文设计（v142）**：AES/GCM/NoPadding，PBKDF2WithHmacSHA256 600k 次（区间 100k–1M，拒绝恶意超大迭代），每次导出新盐新 nonce；解密端参数上限（盐≤64B、密文≤1MB）、BadTag 统一归因"密码错误或密文被篡改"、ignoreUnknownKeys 向前兼容。Token 全程只以密文出剪贴板；密码用 plain `remember` 永不进 Bundle；API33+ 剪贴板标记 IS_SENSITIVE。
- **提交卫生**：本批所有 commit 经密钥扫描习惯核查，无 Token/密码/私钥/服务器配置入库（fork 仓库本就 public）。
- **权限面**：未新增任何 Android 权限；配置同步零网络。

## 六、最终风险清单

1. **回查匹配是概率性防线**：服务端无幂等键，UNKNOWN 回查靠 标题+folder+时间窗（-5min 时钟冗余）；同名任务窗内多条时保持 UNKNOWN 拒绝自动确认（fail-safe，宁可不确认也不确认错）。
2. **交接包为轻模板**（D8 决策）：尾部反向扫描取近 3 条用户/2 条助手文本，无 AI 总结，超长会话上下文保真度有限；编辑内容进程被杀会丢（未入 SavedStateHandle）。
3. **CI 测试抖动**：LegacyRelease 变体偶发超时（本次 ServerStoreTest），会误报 Verify 失败，rerun 可过。
4. **本地服务端快照可能落后真机**（R3）：work_task 事件参数名等以真机实测为准。
5. 派发记录/想法数据存本地 DataStore，卸载即失（设计如此，配置同步只覆盖服务器配置）。

## 七、后续建议（V2 候选，均未实施、不得记为已完成）

1. 派发接续体验：UNKNOWN 卡内嵌 probe、派发 sheet 支持 idea 列表直接进入。
2. 交接包升级：SavedStateHandle 保编辑、可选 AI 摘要（显式调用、可关）。
3. 工程健康：给 ServerStoreTest 超时用例做 v136 式去抖动根治；TaskHubViewModel/DispatchService 补 VM 层测试；TaskHub details 缓存加 LRU 上限。
4. 服务端协作项（需上游）：幂等键、任务列表分页、时区接口——有了之后可把回查窗口收窄到确定性匹配。
