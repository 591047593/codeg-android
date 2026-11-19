# SHELL1 v3 契约对照（2026-11-19，对照 codeg v0.46.0）

> 这是一次**网页端契约冒烟核对**的结果记录，不是新批次任务单。目的是回答：手机端
> 壳对网页行为的假设，在 v0.46.0 上还成不成立。

## 方法

- 参照源码：`spacering-net/codeg` tag `v0.46.0`（2026-11-18），只读。
- 只看壳真正依赖的东西：`localStorage` 的键、DOM 结构/选择器、Web API 的存在性、
  导航与外链行为、文件选择契约。不比对功能清单（套壳版的功能由服务器版本决定）。

## 结论：仍然成立的契约

| 契约 | 网页端位置 | 壳的位置 |
| --- | --- | --- |
| 令牌键 `codeg_token` | `src/lib/transport/web-auth.ts` `TOKEN_KEY` | `server/ServerFeature.kt` |
| 同 origin 提供网页 + `/api/*` + `/ws/*` | `src-tauri/src/web/mod.rs` 静态目录 | 服务器配置校验 |
| `blob:` / `data:` / `<a download>` 下载 | `src/lib/save-file.ts`、`src/lib/api.ts` `openBrowserDownloadUrl` | `download/DownloadScript.kt` |
| `meta[name="theme-color"]`（每媒体一条） | `src/app/layout.tsx` viewport + `theme-provider.tsx` | `display/DisplayFeature.kt` |
| WebSocket 用子协议 `codeg-token.*` | `src/lib/transport/ws-auth.ts` | 无需处理（WebView 原生 WebSocket） |

## 修掉的 5 个断点

1. **文件夹上传**（新）——网页有 `<input webkitdirectory>`
   （`src/components/layout/workspace-upload-dialog.tsx`、
   `aux-panel-file-tree-tab.tsx`），WebView 以
   `FileChooserParams.MODE_OPEN_FOLDER` 送上来。原实现只认 `MODE_OPEN(_MULTIPLE)`，
   于是直接取消 → "选择文件夹"静默无效。现改为 `ACTION_OPEN_DOCUMENT_TREE`，
   树 URI 直接交回 Chromium 展开。
2. **SRN 账号登录**（0.46 新增）——`src/lib/srn-account/navigate.ts` 用
   `location.assign()` 把顶层导航到 `https://spacering.net/...`，回调再回
   `/workspace?srn=…`。原实现把所有跨 origin 顶层导航丢给系统浏览器，
   登录进去回不来。现改为：顶层导航到 `spacering.net` 及其子域留在 WebView；
   另外 **主框架 HTTP(S) 重定向一律留在 WebView**（反向代理登录页也靠这条）。
3. **返回键弹层识别**——v0.46.0 的 `combobox` 与 `drawer` 换成了 Base UI，
   它们**没有 `data-state`**，只有 `data-slot` 与布尔 `data-open`/`data-closed`。
   原选择器只找 `[data-slot="*-content"]` 的几个 Radix 名字，且判据依赖
   `data-state !== 'closed'`。现补齐 `select-content`、`combobox-content`、
   `hover-card-content`、`dialog-content`、`alert-dialog-content`、
   `dropdown-menu-sub-content`、`context-menu-sub-content`、`drawer-popup`，
   并额外识别 Streamdown 全屏视图（`[data-streamdown="table-fullscreen"]` 等，
   `role="dialog"` 但无状态属性，只在打开时存在）。
4. **网页通知**（整块缺失）——`src/lib/notification.ts` 用标准
   `Notification.permission` / `requestPermission()` / `new Notification()`；
   Android WebView **没有这个 API**，网页因此判为 `unsupported`，后台的
   回合完成、权限请求、提问、报错提醒全部不显示。新增 `shell/notify/`：
   - `NotifyFeature`：`POST_NOTIFICATIONS` 运行时权限、通知渠道、
     点击回到 App 并把 `onclick` 交回页面（冷启动补投）；
   - `NotificationScript`：桥接同名 API，`permission` 是实时 getter，
     `requestPermission()` 轮询到原生给出三值答案。
   - 顺带补 **Page Visibility 同步**（`shell/web/VisibilityScript.kt`）：
     网页默认 `when: "hidden"`，即只在 `document.hidden` 为真时发通知，
     而 Android WebView 在 App 退后台时并不可靠地置 `hidden`。
     壳在 `onPause`/`onResume` 里覆写 `document.hidden` 与
     `visibilityState` 并派发真实 `visibilitychange`（对原生会上报的
     版本取 OR，不破坏值）。这同时修好草稿落盘（`message-input-draft.ts`）
     与账号状态回读（`srn-account/web-source.ts`）。
5. **`window.confirm` / `window.prompt`**——网页用它们在关闭未保存的文件页签前
   确认（`src/contexts/workspace-context.tsx` 四处）和为"选目录"输入服务端路径
   （`src/lib/platform.ts`）。原壳的 `WebChromeClient` 不实现这两个回调：
   `confirm` 直接返回 `false`、`prompt` 返回 `null`，表现为页签不问就关、
   选目录无反应。新增 `shell/dialog/DialogFeature`：原生对话框（Compose），
   只对服务器 origin 生效，返回键=取消，页面导航/渲染进程消失时统一取消。

## 明确不做（记录理由）

- **图片剪贴板**（`navigator.clipboard.write/read` + `ClipboardItem`）：
  网页在非安全上下文里已自降级为"不支持"，会给出下载/提示，不假装成功。
  Android WebView 侧接这组 API 要主线程投递 `ClipData` 图片，收益低风险高。
- **`crypto.randomUUID`**：网页有 `getRandomValues` 兜底（`src/lib/utils.ts`）。
- **Service Worker**：网页只在安全上下文注册，局域网 http 下本就不注册，
  与壳无关；`onPause` 时 `pauseTimers` 也未启用，避免打断页面计时。
- **`webkitdirectory` 结果在 App 内的二次消费**：树 URI 交给 Chromium 展开是
  WebView 既有机制；若某厂商 WebView 不展开，会表现为上传空目录（真机用例覆盖）。

## 真机用例（追加到 tests/）

见 `tests/V5-contract.md`。
