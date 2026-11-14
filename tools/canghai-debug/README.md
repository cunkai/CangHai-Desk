# 沧海一键调试工具（v1 · 最小验证）

不打包 APK、不连手机，在浏览器里跑**真实前端文件 + 假原生层**：左边是手机端页面，右边是大屏桌面 + Dock 层。改了前端文件，刷新就生效。

## 怎么跑

需要 Node.js（只用内置模块，零依赖）。

**Linux / macOS：**

```sh
./debug.sh
```

**Windows：** 双击 `debug.cmd`（或在 cmd 里跑）。

打开 `http://127.0.0.1:8931/`。**必须走 http**：桩是服务器注入的，`file://` 打开页面没有注入、桥全是空的。

- 换端口：`CANGHAI_DEBUG_PORT=8080 ./debug.sh`
- 资源根目录默认是工具所在仓库的 `app/src/main/assets`（工具放在 `tools/canghai-debug/` 时零配置）；放别处时用 `CANGHAI_DEBUG_ROOT=/path/to/assets ./debug.sh` 指定。
- 停止：Ctrl+C（Windows 关掉窗口）。

## 架构（10 行）

1. `server.js` 服务仓库的真实 asset 树（不拷贝，改了刷新即生效）。
2. 三个页面（`phonehome/index.html`、`canghai/desktop/index.html`、`canghai/dock/index.html）在第一个 `<script>` 之前被内联注入桩。
3. `fake-core.js` 是共享假 CanghaiStore，挂在壳页顶层 `window.__chFakeNative`，三 iframe 同源共享。
4. `stub.js` 按页面定义桥对象（`CanghaiPhone` / `Canghai`+`Aosen`+`CanghaiCore` / `CanghaiDock`），全部委托给假核心。
5. 状态（快照、Dock 钉、HTML 应用、鼠标库、用户资料、偏好）持久化到壳页 localStorage 单键 `canghai.debug.fake.v1`。
6. 写操作后扇出同步：桌面 `__canghaiSyncApps/__canghaiSyncDesktop/__canghaiSyncCursor`、Dock `__dockRefresh`、手机 `__chReloadState`。
7. `CHCore.invoke` 的 fs 命名空间走内存文件系统（镜像 CoreFs.java 的方法名、返回形状、错误码）。
8. 桌面导出走真实浏览器下载；导入走与 `CanghaiStore.importDesktopBundle` 相同的校验与清洗。
9. webview 命名空间由壳页在桌面窗格上方放一个真 `<iframe>` 覆盖层模拟，矩形由设备像素除以桌面页 DPR 再加桌面 iframe 位置换算。
10. Dock iframe 全尺寸盖在桌面上，壳页按 Dock 上报的 `reportTouchableRegions` 矩形开关它的 `pointer-events`，点 Dock 之外落到桌面。

## 壳页工具栏

- **全部刷新**：三个窗格一起刷新。
- **重置假状态**：清 localStorage 里的假状态并刷新壳页（回到全新设备）。
- **状态 JSON**：新窗口显示当前 `getState` 的完整 JSON。
- 状态行 + 底部日志：最近一次桥调用及参数（调试用）。

## 验证了什么（无头实测，见 `verify.cjs`）

1. 壳页加载、三窗格启动、零页面错误/异常。
2. 手机页点「导出桌面」→ 假核心记下调用并触发真实下载；导入合法 `.canghai-desktop` 包 → 桌面快照变为导入内容。
3. 桌面启动台打开「浏览器验证」（webview-probe），点「打开」→ 覆盖层出现在探针 hole 矩形位置；拖动探针窗口标题栏 → 覆盖层 1 秒内跟上。

无头环境小差异：此 headless_shell 构建会把中文下载文件名改成 `download`（ASCII 名正常），桩传的 `download` 属性本身就是中文名，有头浏览器里文件名正常；验证脚本断言的是落盘文件的内容。

## 仍需真机的事（假层盖不住的）

- **原生内容窗的真实挂载**：真机是 `WindowManager.addView` + `TYPE_PRIVATE_PRESENTATION` 挂在虚拟屏上；壳页只是个 iframe 覆盖层，层级、输入路由、BadTokenException 这类问题只能真机验。
- **特权链路**：Root / Shizuku、建屏、切桌面、安卓应用列表与启动——假层一律无特权。
- **真实文件落盘**：fs 是内存的，刷新就丢；下载目录、SAF 选目录、外置存储迁移都没接。
- **GitHub**：`request` 只回 canned 空结果 `{status:200, body:'[]'}`，`download` 直接报错；仓海应用的真授权与下载只能真机验。
- **局域网大厅**：`lan` 命名空间只回「未启动」。
- **输入法、触摸手感、性能帧率、外接屏热插拔**：一律以真机为准。
- **文件选择器**：文件夹模式（`MODE_OPEN_FOLDER`）是壳的 `onShowFileChooser` 行为，浏览器里表现不同，真机另验。

## 文件

| 文件 | 作用 |
|---|---|
| `server.js` | 静态服务器 + 桩注入 |
| `fake-core.js` | 共享假原生核心（状态、fs、webview、导入导出校验） |
| `stub.js` | 三页桥对象定义 |
| `shell.html` | 壳页（左手机 / 右桌面+DOCK / 工具栏 / 覆盖层） |
| `debug.sh` / `debug.cmd` | 一键启动（Linux/macOS / Windows） |
| `verify.cjs` | 无头验证脚本（需 `~/workspace/tools/headless-test/` 的 `ws` 模块与 headless_shell） |
| `README.md` | 本文件 |

本工具是开发工具，不进 shipped 代码；运行时绝不修改仓库文件。
