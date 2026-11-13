# CangHai Desk（沧海桌面）

> 把你的 Android / 鸿蒙卓易通设备，变成一台可外显、可串流、可接管键鼠的桌面终端。

CangHai Desk 是一个开源的桌面化工具：它把手机或平板通过 **一线通（USB-C 直连显示器）** 或 **Moonlight 串流** 扩展为第二块屏幕，并在其上运行自带的 Web 应用生态。应用同时兼容 **Android 原生** 与 **鸿蒙卓易通（HarmonyOS 的安卓兼容运行环境）**。

[English version](./README.en.md)

## 特性

- **外屏桌面直显**：通过系统 `Presentation` / DeX 能力，在副屏（显示器、扩展坞）上渲染完整桌面，不依赖任何定制 ROM。
- **一线通直连**：支持 USB-C 一线通（DisplayPort Alt Mode）将设备直连显示器；输入设备可按物理端口绑定到指定屏幕。
- **Moonlight 串流**：内置 Sunshine 服务端 + Moonlight 客户端，既可作为串流源把设备画面推送到 PC / 手机，也能作为客户端连接其它 Sunshine 主机。
- **键鼠接管**：将实体键盘、鼠标直接接管并路由到目标屏幕，实现类桌面操作。
- **权限直连（Root / Shizuku）**：无需系统输入法或无障碍服务，直接通过 Root 或 Shizuku 获取所需特权，开箱即用。
- **内置 Web 应用**：自带计算器、文档、Markdown、音乐播放器、游戏等轻量应用，开箱即用。

## 运行环境

| 环境 | 支持情况 |
| --- | --- |
| 最新 Android 手机 | ✅ 原生支持 |
| 鸿蒙卓易通（HarmonyOS 安卓兼容层） | ✅ 可运行 |

> 已在 **骁龙 8 Gen2** 与 **麒麟 8020** 平台测试通过。
> 注：一线通与副屏直显依赖设备硬件及系统对 `Presentation` / DeX 的支持。

## 构建

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 权限说明

- **Root**：提供完整能力（输入设备绑定、系统级操作）。
- **Shizuku**：免 Root 的替代方案，授权后即可使用大部分功能。
- 应用**不**申请任何输入法或无障碍权限，输入交互全部走系统原生键盘。

## 目录速览

- `app/src/main/java/com/aosen/shizukudex/` —— 应用核心（桌面、串流、外设、权限）
- `app/src/main/assets/canghai/` —— 内置 Web 应用与桌面前端
- `app/src/main/java/com/alkaidlab/sunshine/` —— 内置 Sunshine 串流引擎

## 开源许可

本仓库以开源协议发布，欢迎贡献。

## 鸣谢

本项目基于以下优秀的开源库与项目构建（自研模块 `aosen_*` / `canghai*` 不在此列）：

- **[Sunshine](https://github.com/LizardByte/Sunshine)** —— 串流服务端引擎，内置 `com.alkaidlab.sunshine` 及其原生模块，由 Moonlight / Sunshine 社区维护。
- **Moonlight / Limelight** —— 串流客户端协议与解码实现。
- **[Shizuku](https://github.com/RikkaApps/Shizuku)**（dev.rikka.shizuku）—— 免 Root 的权限桥接。
- **[Rikka Tools Refine](https://github.com/RikkaApps/Refine)**（dev.rikka.tools.refine）—— 隐藏 API 调用代理。
- **[LSPosed HiddenApiBypass](https://github.com/LSPosed/HiddenApiBypass)**（org.lsposed）—— 绕过 Android 隐藏 API 限制。
- **[BlurView](https://github.com/Dimezis/BlurView)**（Dimezis）—— 实时毛玻璃模糊视图。
- **[Kotlin](https://kotlinlang.org/) & [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)**（JetBrains）—— 语言与协程。
- **[Gson](https://github.com/google/gson)**（Google）—— JSON 序列化。
- **[AndroidX AppCompat / Annotation](https://developer.android.com/jetpack/androidx)**（AndroidX / Google）—— 兼容性框架。
- **OpenSSL**（`libcrypto` / `libssl`，由 Sunshine 引擎依赖）—— 加密与 TLS。

---

其他语言：[English](./README.en.md)
