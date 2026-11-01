# EXECUTION-LOG（Mobile Hub V1 自主执行日志）

## v137 — 收尾 ✅ 2026-11-01
- 确认 fbf2491 已在 fork/main，tag v1.0.1-fengx.27 已推，CI Verify+Release 双绿（run 41224238014 / 41224249442），双 APK 已挂 Release。工作区干净。无需新提交。

## v138 — 只读审计 ✅ 2026-11-01
- 双侦察代理并行：服务端 API 能力矩阵（20 项 + 附加 A-D）+ Android fork 架构盘点。
- 交付：本目录 5 份文档（Capability-Matrix / Architecture-Audit / PRD-Gap-Analysis / Implementation-Plan / Risk-Register）。
- 关键修正：状态枚举 `preparing`（非 setting up）；定时= create 后另调 work_task_schedule（RFC3339 UTC，仅 todo）；无幂等键；无服务器时区接口；任务列表无分页。
- Commit: 见 git log v138。

## 状态文件
- VERSION-STATUS.md / KNOWN-ISSUES.md / DECISION-LOG.md 同目录维护。

## 批v139：跨设备任务状态中心（2026-11-01 发版）
- 交付：work_task 全套端点封装（CodegClient，手写容错解码防脏字段）；TaskBoard 四列投影（todo/queued→待执行、preparing/running→执行中、awaiting_input/review→需要处理、merging/done→已完成、failed/canceled→失败/取消、未知→未知列，绝不归完成）；TaskHubViewModel 轮询（前台 25s、_active 门控、3s 设备超时、ghost 任务过滤）；TaskHubScreen（筛选/归档开关/详情 events+files+diff/按状态操作/危险操作二次确认）；入口挂 Activity 页三态切换之「任务」。
- 双重审查（对抗+墨菲）发现并修复 11 项：轮询一次性分支致死、busyKey 竞态双击、超时误判 OK 清空任务、幽灵任务、Scaffold padding、后台照轮询、隐藏列表抢焦点、diff 裸 JSON、时区偏移字典序排序、详情失败缓存空等。
- 测试：TaskBoardTest 7 例 + 全 flavor 编译 + testLegacyDebugUnitTest 绿。
- 发版：37f58c0 → fork/main，tag v1.0.1-fengx.28（versionCode 30），CI Release 绿（run 41235240374）。

## 批v140：想法收件箱 + 统一任务派发（2026-11-01）
- 交付：IdeaStore/IdeaEntry 本地想法（DataStore，非 Room）；DispatchStore/DispatchRef 派发记账账本（PENDING/CONFIRMED/UNKNOWN，上限 50 只逐出 CONFIRMED）；DispatchPipeline（taskConfig 蛇形 JSON、findCreatedTask 回查匹配：精确标题+folderId+时间窗-5min 时钟冗余，多条同名单任务候选→保持 UNKNOWN；toUtcRfc3339）；InboxViewModel（预检 health→listFolders+acpListAgents、派发记账→work_task_create→confirm/UNKNOWN/硬失败、probe 回查（失败≠未找到）、discard、redispatch 用账本快照原样重发+保留定时意图+硬失败写回原账）；InboxScreen（想法 CRUD+复制、派发 ModalBottomSheet 设备/项目/Agent chips+日期时间选择器+UNKNOWN 卡片）；Activity 页三态切换之「想法」；中英 strings。
- 双重审查共发现 24 项（对抗 12+墨菲 7 新增），修复 Critical/High/Medium 全部：isAmbiguous 补 ApiError.Transport（否则断连被判确定失败→删账→双建）；probe 网络失败不再误报"未找到"；PENDING 崩溃残留 init 清扫→UNKNOWN；dispatching 同步置位防双击；redispatch 竞态防删账+restoreOnHardFail；DispatchRef 快照补 description/agentTypeWire/dispatchedAtEpochMs/scheduleLocalMillis（进程被杀后回查窗口不丢）；loadTarget 代际号防旧响应覆盖；CancellationException NonCancellable 清理后重抛；folderId==null 行不匹配；多候选 singleOrNull；标题限长 120 双侧一致；probe 并发守卫；错误跨 sheet 串扰清理。
- 未修（Low，记 KNOWN-ISSUES）：过去时间选择仅服务端语义、probe 无进行中指示、文件夹 chips 长列表滚动、解码失败静默清空为既有模式。
- 测试：DispatchPipelineTest 5 例 + 双 flavor 编译 + testLegacyDebugUnitTest 绿。

## 批v141：跨设备会话交接（2026-11-01）
- 交付：DispatchService 抽为 @Singleton 共享派发管线（InboxViewModel 委托，账本/UNKNOWN/CE清理/restoreOnHardFail 语义保真）；DispatchTargets+DispatchTargetLoader 目标选择器共享层（profiles 由各 VM 自己 collect，设备失败不再清空芯片）；HandoffPackage/HandoffPackageFactory（12字段交接包，尾部反向扫描取近3条用户/2条助手文本做轻模板填充，无 AI 总结，D1）；HandoffViewModel（模式B只读 Git 预检 git_status/get_git_branch 按源项目路径，路径未知→"未验证"不伪造；派发复用 DispatchService）；HandoffSheet（六字段编辑+A/B模式chips+目标选择+派发）；入口=会话详情页溢出菜单「生成交接包」（未关联服务器→snackbar 提示）；PENDING 崩溃清扫下沉 DispatchService.sweepPendingToUnknown，收件箱与交接页 init 都调；与既有 v121 同机重连式 handoff（引用+JSON导出）并存互补。
- 双重审查（对抗+墨菲）发现并修复：loader 失败清空设备芯片（中回归）；模式B路径未知时伪造"0未提交"；UNKNOWN 后按钮仍可点（加 unknownRef 禁用）；旋转丢 Sheet（rememberSaveable）；巨量 transcript 主线程全量拼接（改尾部反向扫描）；cap 改码点安全截断；runCatching 吞 CE；菜单静默失败；onDone 时序；profiles 双写。
- 测试：HandoffPackageTest 4 例 + 双 flavor 编译 + testLegacyDebugUnitTest 绿。
- 发版：tag v1.0.1-fengx.30（versionCode 32）。

## 批v142：双手机加密配置同步（2026-11-01）
- 交付：ConfigSyncCrypto（AES-256-GCM AES/GCM/NoPadding + PBKDF2-HMAC-SHA256 600k、每导出新 salt16/nonce12、SecureRandom、Base64-URL-safe JSON envelope、解密参数上限 iterations 10万-100万/salt≤64B/密文≤1MB、BadTag→"密码错误或被篡改"、ignoreUnknownKeys 前向兼容）；ConfigSyncViewModel（导出读 profiles+Keystore token→payload（只导完整条目，跳过计数提示）；previewImport 解密+schema 校验先于一切写入、≤200 台上限；applyImport 顺序写、失败带已写入计数并停、覆盖当前选中设备加提示；ImportCandidate 全条目可勾选，新设备默认导入、冲突默认保留本地）；ConfigSyncContent（导出/导入对话框、密码字段 remember 不进 Bundle、进入页面清残留、剪贴板 API33+ IS_SENSITIVE 标记、复制失败提示）；设置页 CONFIG_SYNC leaf。全程零网络。
- 双重审查发现并修复：密码 rememberSaveable 落 Bundle（高危）；applyImport 无 busy 守卫；粘贴文本清洗 \r\n/零宽字符；导出跳过无提示；新设备无法排除导入；1MB→数千行预览拖垮对话框；覆盖选中设备静默换后端；部分导入失败丢计数；剪贴板失败仍显示成功；envelope Json 未开 ignoreUnknownKeys。
- 测试：ConfigSyncCryptoTest 7 例（roundtrip/错密码/篡改/每次新盐nonce/版本拒绝/坏文本/恶意KDF参数）+ 454 全量单测 + 双 flavor 编译绿。
- 发版：tag v1.0.1-fengx.31（versionCode 33）。

## 批v143：真机反馈全面返工 + 十轮对抗审查（2026-11-01）
- 背景：主人装 v1.0.1-fengx.32-legacy 反馈①动态页闪退②任务/想法/交接等新功能"找不到"（实际藏在动态页）。v143 先做闪退加固+TabRow 三页签+入口惰性创建；随后按主人要求做十轮多维度对抗子代理审查（≤2 并发），每轮修复后编译+454 单测+双 flavor 编译再进下一轮。
- R1+R2（崩溃面+UI/双语言，d200a9a）：DataStore 读失败其实多在写路径/未知路径——全部裸协程兜底；probe/redispatch/discard/sweep 全链 try/catch；46 条引入串默认库补英文。
- R3+R4（双创建闸门+任务中心，e1fff05）：清扫年龄门槛2min（防误杀在途请求）；hasUnresolvedDuplicate 台账查重（后扩为含 PENDING）；autoStart 持久化进 DispatchRef；交接补文件夹失效校验+autoStart=false；probe 多同名不认领；redispatch 保留原 autoStart/降级过期定时/UNKNOWN agent 不重发；send 失败恢复 restoreOnHardFail；任务详情重取键、全局busy、半失败不缓存、幽灵 details 清理、解码不吞格式变化、时间戳 offset 回退。
- R5+R6（资源双语言+UX 走查，b5bc468）：settings_leaf_config_sync 中文串混入默认库（英文用户可见中文，H）；两处引号转义；交接中断安全（取消不再删台账→markUnknown、reset 拒绝在飞行中清态）；收件箱旋转保留编辑器/派发单（id 化 rememberSaveable）；回查 busy 反馈；提交中卡片可见+30s 周期清扫；任务中心 hasLoaded 空态闸+详情失败可重试+按钮禁用不消失+错误横幅滚入视野+幽灵过滤器复位；配置同步导入结果可见化；15 条死串清理；52 处 VM 层硬编码中文留档为技术债。
- R7+R8（并发竞态+legacy API28，b41f139）：AppViewModel 四处裸协程护栏（闪退级 H）；send() 内 Mutex 原子查重预留（TOCTOU 保险）；tick 去重防切页重复 fan-out；详情 epoch 防旧快照回填；redispatch 早期拒绝/取消路径追回台账；活动页先读后取消；R8 结论=API28 零崩溃级问题（java.time 全 API26+，动态色未用，唯二 SDK_INT 守卫均正确）。
- R9+R10（端到端墨菲+终极红队，ba71d48）：**R10 对照服务端 Rust 源码抓出致命线上 bug——work_task_create 嵌套 draft 发 folderId 而服务端 WorkTaskDraft 无 rename_all 要求 folder_id（全量派发会 4xx）；taskConfig 六键 camelCase 被服务端 serde(default) 静默忽略（任务会以空 prompt 创建）**——均已改 snake_case+测试同步；changed_files 读 file、files_changed 按计数、deferred_at 按字符串、workTaskDefer 补必填 deferred；sendJsonElement 不再吞传输错误（超时→UNKNOWN 而非删台账）；回查二遍宽匹配（慢时钟不误报"未找到"）；派发前过期定时归一+文件夹失效校验；空态闸含提交中卡；密文剥离空格；V138 能力矩阵文档字段名勘误（文档 camelCase 说法是根因）。
- 教训沉淀：**派发管线此前九轮全绿但从未对过服务端源码字段名，R10 以 /mnt/Projects/codeg/repo/src-tauri 为准逐一核对才抓出**；V138-API-Capability-Matrix.md 的"全 camelCase"是根因性错误文档。
- 发版：tag v1.0.1-fengx.33（versionCode 35），双 APK 挂 Release。
