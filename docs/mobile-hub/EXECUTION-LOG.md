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

## 批v144：真机堆栈定位闪退 + 「提交结果未知」实测根因 + 派发默认选项 + 三列滚轮选择器（2026-11-01）
- 背景：主人装 v1.0.1-fengx.33-legacy 反馈①点任务页仍闪退（并附上 设置→关于→Last crash 真实堆栈）②派发 sheet 每次要逐行手选设备/项目/Agent，太僵硬③提交后仍显示「提交结果未知」。
- 闪退根因（堆栈实锤）：TaskHubViewModel `_active` 声明在 init 之后，而 Dispatchers.Main.immediate 下 init 里 viewModelScope.launch 同步执行到 combine 收集 `_active` → NPE on null Flow（Combine.kt:28 ← TaskHubViewModel.kt:137）。修复：`_active`/`setActive` 上移至 init 之前（SessionListViewModel `_agg` 同款地雷一并拆除），附注释防回潮。
- 「提交结果未知」根因（对真实服务端 curl 实测）：work_task_create 被服务端拒绝时（serde 422 / DbError→500 如 folder 不存在）**不会创建任何任务**，但 App 端把 5xx 一律按「模糊失败」markUnknown → 每次确定性失败都显示「提交结果未知」。修复三连：①isAmbiguous 收窄为仅 IOException/Transport（服务端有响应=确定失败，Rust 源码核实所有拒绝路径都在插入前行）；②ServerError 解析补 detail 字段（如 "not found: folder 0"），HardFailed 消息带根因；③真模糊（断连）时 markUnknown 后自动做一次只读探查（1.5s 延迟+标题/folder/时间窗匹配），命中即自愈 CONFIRMED，用户根本看不到未知卡。
- 新功能一（派发默认选项）：设置→通用→「派发默认选项」——默认设备/项目/Agent 三行（Agent 可选「自动」），存 AppSettingsStore 四个新键；派发 sheet 与交接 sheet 打开时自动预选（设备切换后项目/Agent 回落默认）。
- 新功能二（三列滚轮选择器）：新组件 feature/common/WheelColumn（LazyColumn+rememberSnapFlingBehavior：松手自动吸附；中项放大1.2x/accent 高亮，上下项缩小渐隐；纯滚动无按钮样式）。派发 sheet 与交接 sheet 的三行 FilterChip 全部替换为三列并排滚轮（每列独立上下滑，列头小标签）。
- 测试：双 flavor 编译绿 + 454 单测绿（testLegacy/testModern debug；Release 首跑一个既有 ServerStoreTest 超时抖动，rerun 绿）。
- 发版：tag v1.0.1-fengx.34（versionCode 36）。

## 批v145：会话内文件上传（系统文件管理器导入 Markdown/PRD/Plan 等文档）（2026-11-02）
- 背景：主人要求——会话对话框此前只支持添加图片，左下角「+」只有快捷消息/专家/命令；需求是补齐「上传文件」，拉起安卓文件管理器导入文档并带进会话，方便喂 Markdown/PRD/Plan。
- 方案（图片逻辑零改动，文件走独立通道）：「+」面板顶部新增「上传文件」入口（accent 图标 + 说明副标题），点击关闭 sheet 并拉起 `ActivityResultContracts.OpenMultipleDocuments()`（`*/*`，任何 provider 可选）；有工作目录时选中即在后台用 `upload_workspace_file` multipart 上传到会话工作目录 `uploads/`（与项目文件浏览器同一条链），发送时在 prompt 追加「附件「名」已上传到 <绝对路径>，请直接读取」提示块，agent 可直接读文件；无工作目录时降级——≤200KB 的文本/Markdown 内联进 prompt 代码块。
- 实现：`FileAttachment`（name/size/absolutePath/inlineContent + isInline）；VM 新增 `addFileAttachments/removeFileAttachment`（SAF 取 displayName/size，逐文件失败计数不中断）与 `fileSummaryNote`（乐观气泡里的人类可读行）/`filePromptAppendix`（机器可读块）；`SessionDetailUi.fileAttachments`；发送链路 send/runSend/attemptSend 全量带参，图片与文件互不干扰（生成式发送仍不消费附件）；输入框上方渲染可删文件 chips（名称+大小/内联标记）；`canSendOverride` 计入文件；首条消息标题空文本时回落文件名。上限 5 文件 × 20MB。
- 门禁：`:app:compileModernDebugKotlin` + `:app:compileLegacyDebugKotlin` + `testLegacyDebugUnitTest` 全绿。
- 分支生命周期：Codeg task 分支 `task/2`（worktree `android-task-2`）→ 合并回 main（merge commit 9bf397e，`main..task/2=0`）→ 删除 worktree 与分支，无孤儿目录。版本号/发版待主人授权。
- **接口契约（给同期 UI 迭代方）**：本批已落在 main（新基线 `d052ac0`）。若 UI 迭代改到输入框 / 「+」面板 / 会话详情页，须保留：①`ComposeInsertSheet(onPickFiles=…)` 的「上传文件」入口；②`SessionDetailUi.fileAttachments` 的可删文件 chips 渲染；③`canSendOverride` 计入 `fileAttachments`；④发送链 `send/runSend/attemptSend` 的 `fileAttachments` 形参（图片与文件互不消费）。其余（TaskBoard/WorkTask/TaskHubViewModel/UX-AUDIT）与本批零文件重叠，UI 迭代直接提交即叠在本批之上，无需再做合并。

## 批v146：UI 与稳定性专项重构（ui重构任务书 全阶段交付）（2026-11-01）
- 背景：主人发 10 张截图（现 App 深色 vs 作者 Web 端新版白色）说 App「太 low」，指定 `plan/ui重构.md`（948 行任务书+7 张结构图）为唯一依据全阶段执行：保留五导航/三平级/全局设备选择器唯一、任务板四段式+紧凑卡、派发轻量化、深色克制视觉。
- 执行：阶段0 审计（UX-AUDIT.md，U1-U10 带行号）→ 阶段1 稳定性 → 阶段2+3 任务板重构 → 阶段4+5 派发+想法 → 阶段6 视觉 → 双子代理对抗审查 21 项 → 审查修复收尾。commit 链 c495772→8274256→b67aeae→8e95101→审查修复→交付文档，共 21 文件 +2300/−610（前四个提交信息前缀 v145 为撞号遗留，勘误见下）。
- 关键修复：①wire key 事故源钉死——亲手核对服务端 Rust 源码：全部顶层 params 均 camelCase，Merge/Complete 的 deleteWorktree 无 serde default（snake 会被静默丢弃然后 422），嵌套 draft/config 才是 snake；新增 WorkTaskWireTest 7 例防再犯（本批曾把方向搞反一次，靠重读源码纠正）。②Decoding（发成功但响应解不开）改判「结果未知」走探测，不再删台账（删了=毁掉唯一线索招双创建）。③确认成功才删想法草稿；UNKNOWN 重派三重门禁+探查精确 0 命中才解锁+时间窗 0 命中加全列表同名兜底+CONFIRMED 写回前墓碑检查。④结果对话框提升到 Screen 层（CONFIRMED 删想法会卸载面板、原对话框一闪而过）。⑤任务页第二排设备胶囊删除，全局选择器唯一；六列改 Web 同款四段式；unknown 中性区永不归 done。⑥派发表单 ①设备与Agent→②描述→③项目+真实分支（git_list_all_branches→base_branch，失败仍可按项目默认）→④创建；Agent/项目列表失败=短提示+重试，原始错误只进日志。⑦聚合超时 3s→10s（冷 DERP 中继误标失败）；看板投影 flowOn(Default)；merge 排队给 actionNote 提示。
- 门禁：双 flavor 全量单测 928 例 0 失败 + assembleModern/LegacyRelease 双绿 + secret 扫描净。CI 首跑折在既有 ServerStoreTest 超时抖动（v144 同款二次发作：真实 DataStore I/O 的 5s 等待窗在 CI 高负载下被击穿，与本批改动无关）——先放宽到 20s 仍在发版 Run 假红（该测试 v142/v144/v146 三批共四次假红），遂确定性改写：废弃与真实文件 I/O 竞速的 500ms 负窗口，改为记录完整发射历史+有界等待未来状态、历史回放断言门控（同强度零竞速），本地三连绿。
- 批号勘误：本批与文件上传批撞号（阶段0-6 提交信息误标 v145，其中 c495772 已推远端）。曾 filter-branch 重写整链消息，因会产生强制推送（禁区）而回退、保留原始历史（备份分支 backup-v146-rewritten）；正式批号=批v146，以本条目与 UX-REFACTOR-REPORT.md 为准，代码内批号注释已统一勘误。
- **不发版**：版本号保持 1.0.1-fengx.34/versionCode 36，不打 tag、不发 Release（待主人真机验收后再授权发版）。报告：docs/mobile-hub/UX-REFACTOR-REPORT.md。

## 批v147：真机四连 Bug 修复（archived 崩溃面/手势穿透/定时完整回显/收件箱净化/死亡红框）（2026-11-02）
- 背景：主人发 4 张截图（当时装的 v34）指定五优先级修复，严禁加新功能：①archived 枚举白屏 ②弹窗手势穿透+顶部不可点 ③定时缺时间选择 ④收件箱混入「已创建任务 #11」回执 ⑤大块红底报错卡。
- ①崩溃面：客户端侧 v146 已用手工解码+KNOWN_STATUSES（含 archived/cancelled）+未知状态原样保留兜住——本批补 WorkTaskStatusTest 5 例钉死永不回潮；**截图报错实为 dell 服务端 500**（dell 在跑的旧服务端构建反序列化自家库里 status='archived' 的行就炸，发生在任何列表过滤之前，客户端无法绕过；其他设备不受影响）。根治=dell 服务端升级（生产操作，待主人授权）。
- ②手势穿透：新组件 core/designsystem/component/SheetGestures.kt（NestedScrollConnection 把内容冒泡上来的全部竖向剩余拖拽+惯性吃掉），垫在派发表/分支表/文件夹表/任务详情表/交接表内容与 sheet 之间——内部列表独立滚动，卡片不再跟着手指走；五表一律 dragHandle=null（顶部拦截层消失，列表第一项可点）+confirmValueChange 拒绝 Hidden（拖拽关闭禁用，关闭只走点遮罩/返回键；已核实 material3 1.3.1 返回键走 scrim→onDismissRequest，不经过被拒的 hide）。
- ③定时回显：日期→时间两段选择链（v35 已交付）保留，回显从 "MM-dd HH:mm" 升级为完整本地化格式（zh「yyyy年MM月dd日 HH:mm」=主人要求的 2026年11月01日 14:30 样式；en "MMM d, yyyy HH:mm"）；时间弹窗标题改「选择时间」；未选完时间红字提示逻辑不变（scheduleNotSet/scheduleInPast 守卫原样）。
- ④收件箱净化：列表只放本地草稿——「已提交等待回执」「已创建任务 #N」独立卡全删（confirmedRefs 仍留在台账供去重闸门，只是不再渲染）；UNKNOWN 恢复入口挪到对应草稿卡（PENDING=灰字「提交中」，UNKNOWN=卡内一行 检查/重派/放弃；未决期间主派发钮禁用置灰）；草稿已删的孤儿 UNKNOWN 保留一张紧凑恢复卡——去重闸门必须始终可解析，不能变成看不见的账。
- ⑤死亡红框：任务板 FailedBanner 红底大卡改柔和文字行+内联重试钮；孤儿 UNKNOWN 卡红底改中性底色+红字标题。会话频道/专家页的 alpha-0.12 行内提示为紧凑行不属「大块红卡」，未动（避免自由发挥）。
- 门禁：本批起本地零编译（主人指示编译打包一律走 GitHub Actions），语法/契约人工核对+新增 5 例单测随 CI Verify 跑。
