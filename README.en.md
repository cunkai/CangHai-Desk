# CangHai Desk

> Turn your Android / HarmonyOS Zhuoyitong device into a desktop terminal with an external display, streaming, and keyboard & mouse takeover.

CangHai Desk is an open-source desktopization tool: it extends a phone or tablet into a second screen via **wired display (USB-C to monitor)** or **Moonlight streaming**, and runs its built-in Web app ecosystem on top. The app works on both **stock Android** and **HarmonyOS Zhuoyitong (the Android compatibility layer of HarmonyOS)**.

[中文版本](./README.zh-CN.md)

## Features

- **External desktop display**: Renders a full desktop on a secondary screen (monitor, dock) through the system `Presentation` / DeX capabilities — no custom ROM required.
- **Wired display (one-cable)**: Supports USB-C wired display (DisplayPort Alt Mode) to connect the device directly to a monitor; input devices can be bound to a specific screen by physical port.
- **Moonlight streaming**: Bundles a Sunshine server + Moonlight client — act as a streaming source to push the device screen to a PC / phone, or connect as a client to another Sunshine host.
- **Keyboard & mouse takeover**: Captures physical keyboard and mouse and routes them to the target screen for a desktop-like experience.
- **Direct privilege (Root / Shizuku)**: No IME or accessibility service needed — acquire the required privileges directly via Root or Shizuku, ready to use out of the box.
- **Built-in Web apps**: Ships lightweight apps out of the box — calculator, document, Markdown, music player, games, and more.

## Runtime environment

| Environment | Support |
| --- | --- |
| Latest Android phones | ✅ Native |
| HarmonyOS Zhuoyitong (HarmonyOS Android compatibility layer) | ✅ Runnable |

> Tested on **Snapdragon 8 Gen 2** and **Kirin 8020** platforms.
> Note: Wired display and external desktop depend on the device hardware and the system's support for `Presentation` / DeX.

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

- **Root**: Unlocks full capabilities (input-device binding, system-level operations).
- **Shizuku**: A root-free alternative — most features work after authorization.
- The app does **not** request any IME or accessibility permission; all text input goes through the system's native keyboard.

## Project layout

- `app/src/main/java/com/aosen/shizukudex/` — app core (desktop, streaming, peripherals, privileges)
- `app/src/main/assets/canghai/` — built-in Web apps and desktop frontend
- `app/src/main/java/com/alkaidlab/sunshine/` — bundled Sunshine streaming engine

## License

Released as open source. Contributions are welcome.

## Acknowledgements

CangHai Desk is built on these excellent open-source libraries and projects (in-house modules `aosen_*` / `canghai*` are not listed):

- **[Sunshine](https://github.com/LizardByte/Sunshine)** —— streaming server engine; bundles `com.alkaidlab.sunshine` and its native modules, maintained by the Moonlight / Sunshine community.
- **Moonlight / Limelight** —— streaming client protocol and decoder.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)**（dev.rikka.shizuku）—— root-free privilege bridge.
- **[Rikka Tools Refine](https://github.com/RikkaApps/Refine)**（dev.rikka.tools.refine）—— hidden-API invocation proxy.
- **[LSPosed HiddenApiBypass](https://github.com/LSPosed/HiddenApiBypass)**（org.lsposed）—— bypasses Android's hidden-API restrictions.
- **[BlurView](https://github.com/Dimezis/BlurView)**（Dimezis）—— real-time blur view.
- **[Kotlin](https://kotlinlang.org/) & [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)**（JetBrains）—— language and coroutines.
- **[Gson](https://github.com/google/gson)**（Google）—— JSON serialization.
- **[AndroidX AppCompat / Annotation](https://developer.android.com/jetpack/androidx)**（AndroidX / Google）—— compatibility framework.
- **OpenSSL**（`libcrypto` / `libssl`, pulled in by the Sunshine engine）—— cryptography and TLS.

---

Other languages: [中文](./README.zh-CN.md)
