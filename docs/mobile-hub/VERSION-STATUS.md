# VERSION-STATUS

| 版本 | 状态 | Commit | 推送 | CI | 备注 |
|---|---|---|---|---|---|
| v137 | ✅ 完成 | fbf2491 | ✅ fork/main | Verify+Release 绿（tag .27） | 多设备渐进加载 |
| v138 | ✅ 完成 | 见 git log | ✅ | docs-only（verify 会跑） | 5 份审计文档 |
| v139 | ✅ 完成 | 37f58c0 | ✅ fork/main | Release 绿（tag .28） | 任务状态中心 |
| v140 | ✅ 完成 | 592819d | ✅ | Verify+Release 绿（tag .29） | 想法收件箱+派发 |
| v141 | ✅ 完成 | 7d72ce4 | ✅ | Verify+Release 绿（tag .30） | 会话交接 |
| v142 | ✅ 完成 | 10c8bc3 | ✅ | Verify+Release 绿（tag .31；Verify 首跑为既有用例 CI 超时抖动，rerun 绿） | 配置同步 |

| 终审修复 | ✅ 完成 | 3ce3d25 | ✅ | tag v1.0.1-fengx.32（v34） | 十维度终审收尾，FINAL-REPORT.md 出稿 |
| v143 | ✅ 完成 | d200a9a→ba71d48（6提交） | ✅ | tag v1.0.1-fengx.33（v35） | 真机反馈返工+十轮对抗审查（崩溃加固/TabRow三页签/双创建闸门/线上字段名致命修正） |
| v144 | ✅ 完成 | f342997 | ✅ | tag v1.0.1-fengx.34（v36；Release 首跑既有用例抖动 rerun） | 真机堆栈定位闪退根因+UNKNOWN 误判修复+派发默认+三列滚轮 |
| v146 | ✅ 完成 | c495772→3448eb8 | ✅ | tag v1.0.1-fengx.35（v37） | UI与稳定性专项重构（四段式任务板/派发重排+分支/想法生命周期/wire key钉死）；含会话文件上传批；CI ServerStoreTest 5s→20s 抑抖 |
| v148 | ✅ 完成 | cbe46bf | ✅ | tag v1.0.1-fengx.37（v39） | 二级卡片拖拽关闭恢复（drag lock 单向闸）；dell 500 与 worktree 两项出规划待拍板 |
| v147 | ✅ 完成 | 65ac774→70e4ff7（suspend签名修正） | ✅ | tag v1.0.1-fengx.36（v38） | 真机五连Bug修复：archived崩溃面单测钉死/五表手势锁+禁拖拽关闭/定时完整回显到时分/收件箱净化/死亡红框清除；dell服务端500需升级（待授权） |
| v149 | ✅ 完成 | a9c55b1 | ✅ | tag v1.0.1-fengx.38（v40） | 会话模式派发（执行方式选择器+createConversation分支+自动跳会话发首条指令+worktree目录过滤+会话探针/重派保模式+SessionAutoStart） |
| v149.1 | ✅ 完成 | ad11c0f | ✅ | tag v1.0.1-fengx.39（v41） | 会话模式派发空白修复（liveTurn 只认 inFlightUserTurnId，服务端出生即 in_progress）+ WheelPicker 行号几何三修（末行吞提交/错位一行/预选偏一行） |
| v149.2 | ✅ 完成 | bf43de0→67a9610 | ✅ | tag v1.0.1-fengx.40（v42；Verify 一次编译错修复+一次既有用例抖动 rerun） | 上传失败修复：图片自适应重编码阶梯（≤550KB）+文件失败原因显形+409自动改名重试+发送前聚合守卫 |
| v150 | ✅ 完成 | 77a22b7→8d54671（15提交） | ✅ | Verify 41434887156 ✅（首跑 41434217560 因动作数单测预期未更新而红，修正后绿）→ tag v1.0.1-fengx.41（v43）→ Release 41436030362 ✅（双 APK+sha256+TG 直链已发） | Mobile Hub V2 UI/UX 重建：四栏导航+动态工作台+悬浮球快捷中心+统一选择器（滚轮退役）+派发表单双宿主契约；交付 UIUX 三文档；推送前完成 10 轮对抗审查 |
| v151 | ✅ 完成 | d6d29b6→f4402a4（13提交） | ✅ | Verify 41597244583 ✅（首跑 41595649199 抓 10 编译错/6 文件——v151 实施八提交首次过编译闸：semantics 左值遮蔽×2、缺 import×3、RecentList 丢 @Composable、compose 1.7 无 MiddleEllipsis；f4402a4 修复后绿）→ tag v1.0.1-fengx.42（v44）→ Release 41598141540 ✅（双 APK+sha256+TG 直链已发） | 工作台折叠/设备条打磨+三屏手势切设备/弹层误关根修+拖拽锁/想法按钮样式/悬浮球+5 动作（默认绑定零变更）/全局新建会话 sheet/搜索「全部」聚合；对抗审查+3 轮多维审查（主人定 30→3） |
| v152 | ✅ 完成 | 6b29bfd（18文件 +656/−246） | ✅ fork/main | Verify 41652191712 ✅（首跑 SearchViewModelTest 在 tearDown `Dispatchers.resetMain()` 抛 kotlinx-coroutines-test 1.9.0 已知并发读改抖动，与本批零关联；`--failed` rerun 后绿）→ tag v1.0.1-fengx.43（v45）→ Release 41655278540 ✅（双 APK+sha256+TG 直链已发） | 五栏导航回归（动态/会话/收件箱/任务板/设置，设置可显隐五栏，会话默认全部设备）+工作台两区默认展开/折叠设置+任务板「全部」设备聚合（底栏/统计卡进入默认全部）+动态页三统计卡跳转+需要处理整行可点且删「去任务板」+悬浮球方形阴影修复（graphicsLayer shape=CircleShape）；三轮审查（2 P1 + 6 P2 全收） |
| v153 | ✅ 完成 | 8cf0b38→7574f94（6提交） | ✅ fork/main | Verify 41685128816 ✅（首跑 ServerStoreTest 计时抖动 TimeoutCancellationException——v146 已定性的基建竞态复现，与本批零文件交集，--failed rerun 绿 4m3s/506 测试）→ tag v1.0.1-fengx.44（v46）→ Release 41687576537 ✅（双 APK modern 51,859,904B / legacy 52,335,248B + sha256 + TG 直链已发） | 五轮对抗审查+UX打磨：R1 并发/导航一致性(1P1+2P2+7P3：搜索全部退出切片残留P1/冷启动旗标/quickNewSession活旗标)、R2 墨菲/迁移(2P2+5P3：startTab saveable/showBottomBar全路由)、R3 可及性+UX(3P2+6P3：onAccent对比度公式化+48dp触达+朗读语义；UX 6条：球面板首卡换全局新建会话/会话页搜索入口/工作台创建任务卡/底栏收件箱徽标/回执去任务板/冷启动补ACTIVITY)、R4 性能/回归对抗(1P2+4P3：sig-only清场不补跑双代理合流)、R5 终局(1P1 裸Row缺import+1P2 launchPersist收口12处)；⚠ 同窗口 Codeg 待办 task/13(批v154) 合并上 main 且其首跑 Verify 红（对方自查修断言中），本批 tag 锚 7574f94 不受影响 |
