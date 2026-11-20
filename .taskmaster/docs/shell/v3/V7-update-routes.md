# V7 更新下载多线路自动测速

## 用户目标与边界

用户指定多个公共代理，在下载前实测并选择最优线路。只处理公开更新 APK，不代理 Codeg 服务器、会话或 GitHub 版本查询，不携带用户 token/cookie。不在本地运行 Gradle 或构建 APK。

## 设计

- 候选：GitHub 直连、gh-proxy.com、ghfast.top、ghproxy.net；代理仅接受本仓库 HTTPS `releases/download/build-N/*.apk` 地址。
- 用户点下载后，4 条线路并发请求同一 APK 前 64KiB，单线路最长 6 秒。拒绝 HTTP 错误、HTML、错误 Content-Range、短读和非 ZIP/APK 文件头；忽略 Range 的正常 200 响应仅读固定样本就关闭。
- 以读取相同样本的总耗时排序，选实测最快者。完整包失败、长度不符或 SHA-256 不符时，清理临时文件并尝试其余通过探测的线路；所有线路失效则明确报错，不无限重试。
- 以独立 HTTPS GitHub API 返回的 asset.digest 校验完整 APK，不能只信任公共代理同时返回的 APK 和校验文件。兼容旧发布时，只从 GitHub 直连取得校验文件；无法取得可信摘要则停止。
- 下载弹窗显示「正在测试下载线路」和当前线路，保持原有百分比与安装入口。测速不是承诺后续永久最快。
- Call 与协程取消联动，设置连接/读/总时限；取消不切换其它线路、不留下半包。完整包下载期限按官方包大小计算，在 3～15 分钟之间；15 秒无读取进展仍会判失败。旧发布原始校验文件单独使用 20 秒预算，不占用 6 秒探测期限。

## 实施与验证

1. 新增 JVM JUnit 回归测试，用标准 ServerSocket 回环 HTTP 夹具模拟快慢、超时、伪 HTML、Range 忽略、下载失败、篡改及取消；先在修复分支 CI 观察红灯。Android Kotlin 单元测试编译环境不暴露 `com.sun.net.httpserver`，不增加新依赖，改用标准套接字实现夹具。
2. `UpdateChecker` 加独立 GitHub API 摘要解析。
3. 新增不依赖 Android Context 的 `UpdateDownloadEngine`，让现有 `UpdateDownloader` 只提供私有缓存目录。
4. `UpdateFeature` 显示测速与选路状态，中英文资源一起更新。
5. GitHub Verify 跑 JVM 测试、现有 Chrome 回归及 Android 检查/构建；只读复查后快进 main 并发版，核对签名门禁、摘要与中文发布说明。

## 真机验收

- Android 16 上点更新下载：先显示测速，再显示线路和进度；弱网不永久停在测速。
- 某代理不可达时不拖住其它候选；选中线路中断时可切换可用备用线路。
- 切换 Wi-Fi/移动网络后重新点重试会重新测速，不缓存永久最优代理。
- 不改变服务器会话连接，代理端不应收到 Authorization/Cookie。
- 当前旧版 App 没有本次新代码，首次升级到此版本需使用旧直连或手动安装；新版本之后的更新才使用自动测速。
