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
| v150 | ✅ 完成 | 77a22b7→848251f（11提交） | ⏳ 待推送 | 待 push 后记录 Verify run id；绿后打 tag v1.0.1-fengx.41（v43） | Mobile Hub V2 UI/UX 重建：四栏导航+动态工作台+悬浮球快捷中心+统一选择器（滚轮退役）+派发表单双宿主契约；交付 UIUX 三文档；推送前完成 10 轮对抗审查 |
