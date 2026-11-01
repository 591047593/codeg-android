# UIUX-TEST-REPORT — Mobile Hub V2 测试报告（批v150 · ui重构任务书 交付物 3）

> 日期：2026-11-02 · 版本 v1.0.1-fengx.41（versionCode 43）· 提交范围 77a22b7…d3255d5（+收尾 docs）。
> **测试环境限制（如实声明）**：dell 无 Android 模拟器/真机，且本地编译/打包属主人 deny-by-default 资源纪律（重活一律走云端 CI）。本批验证 = 本地静态审查 + ui1/ui2 对照 + CI 云端闸；**不含真机截图与自动化 UI 测试，也不制作任何假截图**。真机行为验收清单见 §4，移交主人装包后逐项打勾，结果回填本文件。

## 1. 本地静态验证（全部通过）

- **剥离源码括号平衡**（正则剥注释/字符串后逐文件计数）：本批触碰的全部文件 working vs HEAD 差量为 0（SessionDetailScreen.kt 维持 HEAD 既有启发式假阳性 −2/−1，CI 一贯绿，与本次改动无关）。
- **结构断言**：InboxViewModel 对 DispatchFormHost 六成员 override 齐全；DispatchSheet 调用点签名与新包装一致；WheelColumn/WheelPicker UI 调用点清零；Dual 路由（chats/folders/search）仍在 barBearingRoutes；FloatingBallCatalog 默认绑定零变更。
- **双语文案对账**：strings.xml 与 values-zh-rCN 同批 +28 条，en↔zh key 集合一致；删除未引用的 quick_idea_save_failed。
- **资源纪律**：本批本地零编译，全部编译验证走 GitHub Actions。

## 2. CI 云端闸

| 关卡 | 状态 | 说明 |
|---|---|---|
| Verify（push main） | 待推送后记录 run id | 编译+单测唯一门禁；ServerStoreTest 超时为既有抖动，假红时 `gh run rerun --failed` |
| Release（tag v1.0.1-fengx.41） | 待打 tag 后记录 run id | 签名校验 + 双 APK + sha256 + Telegram 直链 |

## 3. 对抗性审查

推送前完成 ≥10 轮多维对抗性审查（编译/空安全、导航回归、聚合状态、VM 生命周期、持久化迁移、深链、双击竞态、数据丢失、红线违反、ui 对照、v146 不变量回归、墨菲组合、性能），结果与本节同步更新。

## 4. 真机验收清单（移交主人，doc2 §四 + 八张截图）

1. 任意页面点悬浮球 → 面板出 2×2 快捷卡+搜索行；「记录想法」≤2 步完成并可从收件箱看到草稿。
2. 面板「创建任务」→ 表单为任务模式（有分支/定时区），派发成功出回执；不经过收件箱。
3. 面板「新建会话」→ 会话模式（无分支/定时区），成功回执可直达会话。
4. 面板「会话交接」→ 选会话 → 落到对应设备会话详情且交接单自动展开。
5. 动态工作台：一眼看到设备在线数/运行中任务数/需要处理预览；原 feed 正常滚动。
6. 四栏底部导航切换无闪烁；会话/文件夹/搜索从工作台与悬浮球可达。
7. 收件箱派发单：设备 chips（错误设备带红点）/Agent chips/项目 sheet 可搜索；定时派发整点回显；同一目标重复派发被双创建闸拦下。
8. 交接单：三滚轮已换 chips+sheet，语义与旧版一致（A/B 模式、git 预检行）。
9. composer 文件夹切换出 sheet（可搜索、路径可见）；旧锚定菜单不再出现。
10. 服务器编辑页连接成功文案为主题绿（非旧硬编码色）。

截图请求：动态工作台 / 悬浮球面板 / 快捷任务表单 / 收件箱派发单 / 交接单 / composer 文件夹 sheet / 收件箱列表 / 任务板 八张（doc2 §四）。

## 5. 未完成事项与剩余风险

- 未做：真机回归（待主人装 v41）、截图八张（环境限制，见 §0 声明）、自动化 UI 测试（仓库无 UI 测试基线，本批未新增——避免造不可运行的能力）。
- 风险①：MainShell 层 sheet 内 hiltViewModel() 解析到 Activity 作用域——QuickActionViewModel 全 app 单例属预期设计（targets 缓存跨次打开复用），但若未来加"每次打开重置表单"需求需改为逐次清 state。
- 风险②：QuickIdeaSheet/QuickTaskSheet 在 sheet dispose 后 rememberSaveable 丢失 = 每次打开全新表单（与旧收件箱 sheet 行为一致，非回归；未完成内容不落草稿，与旧版同）。
- 风险③：WorkbenchViewModel 60s 轮询在低端机上属新增常驻协程（gated by tab+foreground，离开即停），首轮数据依赖各设备 health/loader，弱网下统计行显示"加载中/失败"为真实状态非假在线。
