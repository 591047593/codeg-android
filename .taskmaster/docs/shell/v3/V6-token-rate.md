# V6 手机实时 Token 速率显示修复

## 范围与设计

- 用户已批准：修复手机竖屏不显示 `tok/s`，修好后通过 GitHub Actions 发版。
- 根因：codeg v0.46.0、v0.47.0 的 `LiveTurnStats` 使用 `hidden` 与 `@[30rem]/turnstats:inline-flex`，容器宽度不足 30rem 时隐藏速率。
- 只在 Android 壳注入样式，不修改服务端、不重新计算 Token、不制造没有数据的速率。
- 在现有 `DisplayFeature.observePage` 同源检查后安装顶层文档样式；脚本额外校验当前 origin。
- 精确匹配 `@container/turnstats` 内 30rem 的速率项及分隔符，显示原有速率；保留耗时、文件统计的原响应式规则和原有换行布局。
- 使用固定 style id，重复注入不叠加；普通 SPA 更新和速率项晚出现时由 CSS 自动覆盖，无需轮询或 MutationObserver。
- 不新增 Android 依赖、不改变桥接权限、不修改其它审查问题。

## 实施计划

- [x] 创建 `scripts/test_mobile_token_rate.cjs`：通过独立临时 Chrome profile、回环夹具服务器运行真实 CSS 容器查询；读取并执行待加入的 Kotlin 注入脚本。
- [x] 先运行 `node --test scripts/test_mobile_token_rate.cjs`，确认窄屏速率仍为 `display:none`，测试按预期失败。
- [x] 创建 `shell/display/MobileTurnStats.kt`，并在 `DisplayFeature.observePage` 调用 `mobileTurnStatsScript(JSONObject.quote(serverUrl))`。
- [x] 重跑真实浏览器测试：320/360/390/430/768px、放大字号、已有文件统计、晚挂载、重复注入、跨来源和子 frame。
- [x] 将同一测试加入 Verify 与 Auto Release，在构建前运行；本地不执行 Gradle。
- [ ] 分支提交并运行 GitHub Verify；通过后快进 main，推送并等待 Verify/Auto Release 完成。
- [ ] 发布正文固定为 `### 更新内容` 与 `### 修复内容`，附 APK 与 SHA-256；确认发布版本和 CI 结果。

## 验收边界

真实 Chrome 布局测试验证注入样式及容器查询行为；GitHub CI 验证 Kotlin 编译、lint、构建和签名。它们不替代 Android 真机操作。

| 编号 | 优先级 | Android 16 真机步骤 | 预期 |
|---|---|---|---|
| V6-01 | P0 | 竖屏打开会话，发送消息，等待智能体持续输出文字 | 耗时旁显示网页原有 `tok/s`，数值随输出更新 |
| V6-02 | P0 | 在 100% 和 150% 网页缩放下重复 V6-01；旋转横屏再回竖屏 | 速率保持可见，统计栏可以换行，不遮挡输入或发送按钮 |
| V6-03 | P1 | 无输出等待、工具执行、回合结束及历史消息 | 沿用网页原本的数据与生命周期，不强行显示虚假速率 |
| V6-04 | P1 | 刷新、切换会话、切换服务器后再次输出 | 样式不重复堆叠，新的会话统计仍可见 |
