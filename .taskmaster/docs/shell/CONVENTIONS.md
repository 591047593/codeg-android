# 套壳批次（SHELL1）任务方约定

> 你是 Codex，负责 `shell/` 下的一个功能插槽。先读本文件，再读你的任务单 `.taskmaster/docs/shell/Sx-*.md`。旧的 `.taskmaster/docs/CONVENTIONS.md`、`tasks/`、`code-index/` 属于已废弃的原生版本，**不要读**。

## 背景
- App 已从原生界面改成 **WebView 套壳**：加载用户所选 codeg 服务器的网页端（服务端在同一个 origin 下提供网页、`/api/*`、`/ws/*`）。网页端是 Next.js 单页应用，令牌存在 `localStorage["codeg_token"]`，未登录时网页会自己跳到 `/login`。
- 目标是"和网页端一模一样"：**不在原生层画任何业务界面**，只做网页在 WebView 里跑不顺的适配。原生界面只有服务器设置、错误页、更新提示这几个。
- 原生旧代码保存在 tag `native-final`，需要参考时用 `git show native-final:<路径>`（例如旧的更新逻辑、服务器存储）。

## 架构（骨架已就绪，不要改）
- `MainActivity` → `ShellActivityHost`（实现 `ShellHost`）→ `ShellApp`（Compose 根）→ `AndroidView(WebView)`。
- 每个功能是一个 `ShellFeature`（`shell/ShellContract.kt`），所有钩子都有空默认实现。WebViewClient / WebChromeClient 的回调由 `shell/ShellClients.kt` 分发给各功能；返回 Boolean 的钩子按 `ShellFeatures.all` 的顺序询问，第一个返回 true 的生效。
- 功能之间不直接互相调用，只通过 `ShellHost`（`loadUrl`、`reload`、`recreateWebView`、`openServerManager`、`currentServerUrl`、`webView`、`activity`、`scope`）。
- **以下文件归协调方，任务方不得修改**：`MainActivity.kt`、`shell/ShellContract.kt`、`shell/ShellClients.kt`、`shell/ShellApp.kt`、`shell/ShellActivityHost.kt`、`shell/ShellFeatures.kt`。如果你认为契约不够用，**不要改**，在最终报告里写清楚缺什么、为什么，用现有钩子先做到最好。

## 冲突域规则
- 只改任务单"改动范围"里列出的文件。一般就是自己的包 `shell/<pkg>/**`，加上自己的资源文件：
  - 字符串：`res/values/strings_shell_<pkg>.xml` 和 `res/values-zh-rCN/strings_shell_<pkg>.xml`（**中英两份都要有，键一一对应**，否则 lint 报 MissingTranslation）。键名统一加前缀 `shell_<pkg>_`。
  - 其他资源同样以 `shell_<pkg>_` 前缀命名。
- **不准新增依赖**（不改 `build.gradle.kts`、`gradle/libs.versions.toml`）。新增依赖会让第三方许可证清单校验失败。现有可用：Compose、Material3、activity-compose、lifecycle、kotlinx-coroutines、kotlinx-serialization、OkHttp、Ktor、DataStore、Hilt。`androidx.webkit` **没有**，只能用平台 `android.webkit` API。
- `AndroidManifest.xml` 默认不准改；任务单明确允许的才改，且只加自己那一段。
- FileProvider 已配置：authority `${applicationId}.files`，路径见 `res/xml/file_paths.xml`（`cache/updates/`、`cache/camera/`、`cache/shared/`），直接用，不要再声明 provider。
- JavaScript 桥（`addJavascriptInterface`）名字统一用 `CodegShell<功能>`，例如 `CodegShellDownload`；只在页面 origin 等于 `host.currentServerUrl` 时才响应调用。
- minSdk 36、targetSdk 36（个人构建只装 Android 16 真机），不需要为更低版本写兼容分支。

## 开发与验证（CI 模式）
- 你在 codeg 为本任务新建的 worktree 里（基于 `shell` 分支）。只在这个 worktree 里提交，不要切换分支，不要动主仓库，不要推 `main`/`shell`/`sit`，不开 PR，不 merge。
- 推送与 CI：`git push -u origin HEAD:task/<任务号>-<短名>` → `gh workflow run verify.yml --ref task/<任务号>-<短名>` → `gh run list --workflow verify.yml --branch task/<任务号>-<短名> -L 1` 取 run id → `gh run watch <id> --exit-status`。失败就 `gh run view <id> --log-failed`，修好再跑，**直到通过**。
- 如果推送被拒（权限审批），在报告里写明，由协调方代推，你不用反复重试。
- 不要操作 adb 或手机，真机测试由协调方统一做。
- 提交信息用英文祈使句，正文写改了什么、为什么；**不加 `Co-Authored-By` 或任何 AI/工具署名**。

## 测试用例
- 在 `.taskmaster/docs/shell/tests/<任务号>.md` 写本功能的真机测试用例（表格：编号 | 优先级 P0/P1 | 步骤 | 预期）。编号用 `<任务号>-01` 这种格式。P0 是必须测的核心流程，写具体步骤（点哪里、输入什么、预期看到什么），协调方会照着在 vivo Android 11 真机上执行。
- 测试服务器：协调方用 `http://127.0.0.1:3082`（adb reverse 到电脑上的测试服），网页端已登录。

## 最终回复
分支名、提交列表、CI run 链接和结果、测试用例文件路径；未完成项；你认为契约（骨架）缺失的地方；需要人工确认的问题。
