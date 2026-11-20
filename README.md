# Codeg for Android

Android 16+ WebView 套壳客户端，直接显示所选 Codeg 服务器的网页。业务功能、登录和会话由服务器网页提供；原生层负责服务器设置及 WebView 适配。

## 使用

打开应用，填写服务器地址（例如 `http://192.168.1.10:3080`），进入网页后登录。服务器须在同一 origin 下提供网页、`/api/*` 和 `/ws/*`。远程连接推荐 HTTPS。

正式版恢复前台时最多每 6 小时检查一次 GitHub Release。发现新版可查看说明、下载并安装；选择稍后会对同版本静默 24 小时。首次安装更新需要在系统设置允许此应用安装未知应用。debug 版不检查更新，当前没有手动检查入口。

更新下载会并行试读 GitHub 直连及三个公共代理（gh-proxy.com、ghfast.top、ghproxy.net）的少量 APK 数据，按本次实测耗时选择可用线路；完整包下载或校验失败时尝试已测通的备用线路。每条测速线路最多等待 6 秒、读取最多 64KiB，弹窗显示测速及当前线路。代理仅接收本仓库公开 Release APK 地址，不携带服务器 Token/Cookie；安装前使用独立 GitHub API 提供的 SHA-256 校验。旧发布缺少 API 摘要时仅从 GitHub 原始地址读取校验文件，无法取得可信校验值则停止更新。公共代理可用性会变化，测速结果只代表当前网络采样。

## 构建

需要 JDK 21、Android SDK 36，以及本地 `local.properties` 配置 SDK 路径。

```bash
./gradlew :app:assembleDebug
./gradlew :app:check :app:assembleDebug :app:assembleRelease --no-daemon
```

无本地 `keystore.properties` 时，release 使用仓库中的公开开发签名（仅供此分支分发测试）。已有安装需要相同 applicationId、签名和更高 versionCode 才能覆盖；私有签名配置和密钥不得提交。

## 发版

`verify.yml` 支持手动指定分支，执行检查和 debug/release 构建，产物保留 14 天。

`auto-release.yml` 在 main 的代码变更或手动触发时发版：完整 Git 历史的提交数为 versionCode，版本名为 `1.1.<提交数>`，标签为 `build-<提交数>`。保留此计数规则及签名即可从同签名 1.1.139 覆盖升级；不要重写或压缩发版历史。流水线发布 APK、SHA-256 文件和提交说明，保留最近十个 build Release。仅在准备正式发版的分支触发此流程。

项目许可证见 [LICENSE](LICENSE)，第三方材料见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [third-party-licenses/](third-party-licenses/DEPENDENCIES.md)。依赖许可证由 CI 校验。
