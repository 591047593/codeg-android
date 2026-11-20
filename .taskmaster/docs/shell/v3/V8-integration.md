# V8 已审查缺口修复与统一发版

用户已批准修改、合并 `feat/update-route-selection` 并发版。基线 `0089bff`，`main` 与线上仍为 `3042816` / build-189。保留 V7 的多代理更新下载。

## 约束

- 不改服务端，不新增 Android 依赖；本地只运行轻量 Node/Chrome 测试，不运行 Gradle/APK 构建。
- 不清空用户配置、登录态或网页存储；不合入未验证提交，不重写 main 历史。
- 本轮不实现断点续传、后台常驻推送或跨设备配置同步。
- 所有原生 JS 桥的 WebView 操作必须在 UI 线程；回调执行前重新验证文档凭证、当前视图与来源。

## 分工与接口

1. 通知 / 剪贴板：规范默认端口 origin、缓存同步权限状态以避免跨线程读取 WebView；通知保存服务器与页面目标，冷启动按可持久化目标恢复，不声称能恢复已消失的 JS onclick；剪贴板读取只为主动粘贴兜底，不绕过浏览器 NotAllowedError。代理人仅编辑 notify/**、web/ClipboardScript.kt、web/WebSettingsFeature.kt 与相应测试。
2. 导航：顶栏箭头和窗口关闭一次退出至记录的工作区入口，系统返回仍逐层返回；不依赖 history.length 判断后退能力；按来源 / 文档 token 防护，按钮支持晚挂载并提供中英文标签。
3. 更新体验：保留下载 Job，提供取消；成功检查后才写 6 小时缓存，失败短退避；加入手动检查、无新版 / 检查失败反馈；错误分类；保留已实现的可信摘要与代理边界。
4. 宿主 / 服务器 / 恢复：服务器管理页覆盖 WebView，不无故重建；启动异步读取配置并提供不破坏数据的重试界面；探测区分鉴权、HTTP 错误及未识别页面；重连只允许一个加载，超时后才重试。
5. 统一回归测试、GitHub Verify、只读复查；快进 main，Auto Release 发布，更新正文使用「更新内容 / 修复内容」，核对 APK、签名门禁、SHA-256 与发布提交。

宿主新增接口（由协调方实现，功能方只能调用）：

```kotlin
fun openServerTarget(serverOrigin: String, targetUrl: String): Boolean
fun checkForUpdates()
```

`openServerTarget` 只接受已保存的服务器与其同源 HTTP(S) 目标，在 UI 线程切换服务器并导航；返回 false 表示拒绝。目标没有会话 ID 时只恢复网页目标和其已有持久化状态，不捏造会话定位。

## 进度

- [x] 通知 / 剪贴板修复与实际脚本回归（23 项）
- [x] 导航出口修复与实际脚本回归（20 项）
- [x] 更新体验及 JVM 回归用例已接入（实际执行待 GitHub）
- [x] 宿主 / 服务器 / 串行恢复及 JVM 回归用例已接入（实际执行待 GitHub）
- [ ] 分支 CI 和复查
- [ ] 合并发版及产物核验
