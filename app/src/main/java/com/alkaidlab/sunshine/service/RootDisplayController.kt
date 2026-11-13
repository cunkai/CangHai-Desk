package com.alkaidlab.sunshine.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.aosen.shizukudex.BuildConfig
import com.aosen.shizukudex.InputTrace
import com.aosen.shizukudex.PrivilegeRoute
import com.aosen.shizukudex.RootInputBridge
import com.aosen.shizukudex.ShizukuCmd
import org.json.JSONObject

/**
 * 纯 Root 通道的 Moonlight 虚拟屏控制器。
 *
 * 背景：原来 Sunshine 的虚拟屏只有一种实现 —— VirtualDisplayUserService，它是靠
 * Shizuku.bindUserService 拉起的 shell 进程（UID 2000）display adapter。纯 Root 设备
 * 没有 Shizuku 授权时，VirtualScreenClient.connect() 直接抛「创建虚拟屏需要 Shizuku
 * 授权」，编码器 Surface 从未绑定到任何 VirtualDisplay，一帧都产不出来，Moonlight
 * 客户端于是在握手后直接掐断连接。
 *
 * 本类实现同一个 ISunshineDisplayController 接口，但跑在**应用主进程本地**，不依赖
 * Shizuku：
 *  - 建屏/释放/改尺寸/换 Surface 走 ShizukuCmd → RootInputBridge，由常驻 root
 *    app_process 以 UID 0 调 IDisplayManager（保住 TRUSTED + 系统装饰）；
 *    root 桥不可用时 ShizukuCmd 会自动回退到应用侧 DisplayManager，**保证一定有画面**；
 *  - 桌面用普通 CATEGORY_LAUNCHER + -f 0x30000000 启动，不使用 HOME 语义，
 *    避免三星 One UI 进入外接屏模式把主界面压到后台；
 *  - 输入走常驻 root 桥的 37 字节定长帧，经 IInputManager 直接注入，
 *    没有逐事件起 su / 解析 shell 字符串；空闲 CPU 为 0。
 *
 * 全部是会话级的低频事务（建屏/释放/重配各一次），视频帧由 VirtualDisplay 直接
 * 写进编码器 Surface，不经过任何 copy。
 */
class RootDisplayController(private val context: Context) : ISunshineDisplayController.Stub() {

    companion object {
        private const val TAG = "AosenRootDesk"
        private const val DISPLAY_NAME = "Aosen_VD_Moonlight"
        private const val PROTOCOL_VERSION = 11
        private val AOSEN_DESKTOP = ComponentName(
            BuildConfig.APPLICATION_ID, "com.aosen.shizukudex.CastHomeActivity")
        private val SAMSUNG_DESKTOP = ComponentName(
            "com.sec.android.app.launcher", "com.honeyspace.dexservice.SecondaryLauncher")
    }

    private var displayId = -1
    private var width = 0
    private var height = 0
    private var density = 240
    private var launcher = ""
    private var mode = ""
    private var homeType = 0

    private var requestedRefreshRate = 60
    private var detail = "纯 Root 通道"

    /**
     * 低频 shell 命令（am start / 桌面页切换）专用单线程。输入路径一律不进这里：
     * 按键和鼠标走 RootInputBridge 的二进制帧，零等待、零 shell、零 ANR 风险。
     */
    private val shellExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AosenRootDesk-shell").apply { isDaemon = true }
    }

    private fun postShell(block: () -> Unit) {
        try {
            shellExecutor.execute {
                try { block() } catch (t: Throwable) { Log.w(TAG, "后台桌面命令失败: ${t.message}") }
            }
        } catch (_: Throwable) {}
    }

    // ======================= 虚拟屏生命周期 =======================

    @Synchronized
    override fun createDisplay(
        width: Int, height: Int, density: Int, surface: Surface, mode: String,
        owner: IBinder, fps: Int, systemDesktop: Boolean, highRefresh: Boolean
    ): Int {
        if (width <= 0 || height <= 0 || !surface.isValid)
            throw IllegalStateException("编码画布无效，无法创建 Root 虚拟屏")
        closeDisplay()
        this.width = width
        this.height = height
        this.density = density
        this.mode = mode
        requestedRefreshRate = if (highRefresh) fps else 60

        // 一次 Binder 事务把 Surface 交给 root UID 建屏。ShizukuCmd 内部顺序：
        // Shizuku binder（Root 下不可用）→ RootInputBridge(root UID) → 应用侧
        // DisplayManager 兜底。第三级兜底是这里最关键的一层：即使 root 桥句柄
        // 因 SELinux / 时序没送达，也一定有一块真实 VirtualDisplay 输出画面，
        // 不会再出现「客户端连上就没帧、直接掐断」。
        val id = ShizukuCmd.createVirtualDisplay(context, DISPLAY_NAME, width, height, density, surface)
        if (id <= 0) throw IllegalStateException("Root 虚拟屏创建失败，编码画布未绑定任何显示")
        displayId = id
        Log.i(TAG, "Root 虚拟屏已创建 id=$id ${width}x${height}@$density, 桌面类型=$homeType")
        SunshineLogManager.log("ROOT_DISPLAY", "创建虚拟屏 id=$id ${width}x${height}@$density")

        // 桌面启动失败不能让整个会话失败：虚拟屏已经绑定编码器，最差也只是
        // 显示空白桌面，但帧流是连续的，Moonlight 客户端不会断开。
        if (!startDesktop(id, "resume")) {
            detail = "虚拟屏已就绪，但桌面 Activity 启动失败；请检查桌面组件或切换桌面类型"
            Log.w(TAG, detail)
            SunshineLogManager.log("ROOT_DISPLAY_WARN", detail)
        } else {
            detail = "纯 Root 通道：root UID 建屏 + 常驻桥输入"
        }
        return id
    }

    /** 与 VirtualDisplayUserService.startDesktop 一致：普通 LAUNCHER 语义，不触发三星外接屏模式。 */
    private fun startDesktop(id: Int, page: String): Boolean {
        val preferred = if (homeType == 1) AOSEN_DESKTOP else SAMSUNG_DESKTOP
        if (launch(preferred, id, page)) {
            launcher = if (homeType == 1) "沧海桌面 · Root" else "官方原生桌面 · Root"
            return true
        }
        // 官方桌面不可用（非三星 / 组件被裁剪）时兜底回沧海桌面。
        if (preferred != AOSEN_DESKTOP && launch(AOSEN_DESKTOP, id, page)) {
            launcher = "沧海桌面 · Root（官方桌面不可用，已回退）"
            return true
        }
        launcher = "Root 虚拟屏"
        return false
    }

    private fun launch(component: ComponentName, id: Int, page: String): Boolean {
        val extras = if (component == AOSEN_DESKTOP)
            " --es desktop_page $page --ei desktop_display_id $id" else ""
        val cmd = "am start --display $id --windowingMode 1 -f 0x30000000" +
            " -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER}$extras" +
            " -n ${component.flattenToString()} 2>&1"
        val out = try { PrivilegeRoute.execute(cmd) } catch (t: Throwable) {
            Log.w(TAG, "启动桌面失败: ${t.message}")
            return false
        }
        // am start 在 Activity 已前台时只输出 Warning，仍视为成功。
        return !out.contains("Error:") && !out.contains("Error type")
    }

    @Synchronized
    override fun closeDisplay() {
        val id = displayId
        displayId = -1
        if (id > 0) {
            // ShizukuCmd 会自动判断是 root-owned 还是应用侧 VirtualDisplay，分别释放。
            try { ShizukuCmd.releaseVirtualDisplay(id) } catch (t: Throwable) {
                Log.w(TAG, "释放 Root 虚拟屏 $id 失败: ${t.message}")
            }
            Log.i(TAG, "Root 虚拟屏已释放 id=$id")
        }
        launcher = ""
        mode = ""
    }

    @Synchronized
    override fun reconfigureDisplay(width: Int, height: Int, surface: Surface, fullscreen: Boolean): String {
        val id = displayId
        if (id <= 0) return "{}"
        try {
            ShizukuCmd.resizeVirtualDisplay(id, width, height, density)
            if (surface.isValid) ShizukuCmd.setVirtualDisplaySurface(id, surface)
            this.width = width
            this.height = height
        } catch (t: Throwable) {
            Log.w(TAG, "重配 Root 虚拟屏失败: ${t.message}")
        }
        return "{}"
    }

    /** 纯 Root 通道不进入三星原生 DeX 的分辨率协商，直接按客户端请求尺寸建屏。 */
    @Synchronized
    override fun configureDesktop(
        width: Int, height: Int, samsungDex: Boolean, owner: IBinder, fullscreen: Boolean
    ): String = "{}"

    /** 不做本机 DeX 发现/确认：那条路径会让上层进入最长 180s 的人工确认等待。 */
    @Synchronized
    override fun prepareLocalDex(owner: IBinder): Boolean = false

    @Synchronized
    override fun blankMainScreen(owner: IBinder): Boolean {
        Log.w(TAG, "纯 Root 通道暂不支持主屏息屏串流，请使用桌面模式")
        return false
    }

    @Synchronized
    override fun setMainPanelOff(off: Boolean): Boolean = false

    @Synchronized
    override fun attachOwner(owner: IBinder) {
        // 本地同进程控制器没有宿主死亡的概念，生命周期由虚拟屏会话自己管理。
    }

    @Synchronized
    override fun destroy() {
        closeDisplay()
    }

    // ======================= 桌面操作 =======================

    @Synchronized
    override fun setHomeType(homeType: Int) {
        if (homeType != 0 && homeType != 1) return
        val changed = this.homeType != homeType
        this.homeType = homeType
        if (!changed) return
        val id = displayId
        if (id <= 0) return
        // 串流进行中切换桌面类型：必须立刻把新桌面拉到前台，
        // 否则要断开 Moonlight 重连才生效（旧实现只改字段，表现为「切换没反应」）。
        Log.i(TAG, "桌面类型切换为 $homeType，热切换 display=$id")
        postShell {
            val ok = startDesktop(id, "resume")
            Log.i(TAG, "热切换桌面结果=$ok launcher=$launcher")
        }
    }

    @Synchronized
    override fun launchApp(component: String): Boolean {
        val id = displayId
        if (id <= 0) return false
        val target = ComponentName.unflattenFromString(component) ?: return false
        // 从网页/桌面 UI 点应用时可能落在主线程，am start 一律丢到后台线程。
        postShell { launch(target, id, "apps") }
        return true
    }

    @Synchronized
    override fun desktopTasks(): String = "[]"

    @Synchronized
    override fun desktopAction(action: String, taskId: Int): Boolean {
        val id = displayId
        if (id <= 0) return false
        return when (action) {
            "back" -> key(KeyEvent.KEYCODE_BACK)
            "home", "apps", "tasks", "quick" -> {
                val page = when (action) {
                    "apps" -> "apps"
                    "tasks" -> "tasks"
                    "quick" -> "quick"
                    else -> "resume"
                }
                // 桌面启动涉及 shell，丢到专用线程，绝不在调用线程同步等输出。
                postShell { startDesktop(id, page) }
                true
            }
            else -> false
        }
    }

    @Synchronized
    override fun showDesktopControls(callback: ISunshineDesktopCallback): Boolean = false

    // ======================= 输入 =======================

    /**
     * Moonlight 鼠标/触摸事件 → 常驻 root 桥的定长二进制帧 → IInputManager。
     * 没有逐事件起进程、没有 shell 字符串解析；失败才退回 Shizuku binder。
     * 注意：本控制器不 recycle event —— AIDL 同进程调用拿到的是调用方持有的对象。
     */
    @Synchronized
    override fun injectMotion(event: MotionEvent): Boolean {
        val id = displayId
        if (id <= 0) return false
        // 诊断记录只在大脑入口与客户端入口各记一次；这里是落地动作，不再重复记。
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val ok = RootInputBridge.inject(
            context, id, event.action, event.buttonState, event.actionButton,
            event.x, event.y, v, h, event.source)
        if (ok) return true
        return try { ShizukuCmd.injectMotionEvent(event, id) } catch (_: Throwable) { false }
    }

    /**
     * 按键/右键等输入一律走常驻 root 桥的定长帧，绝不用 shell 命令。
     *
     * 旧实现调 `input -d <id> keyevent`，RootCmd.execute 会同步
     * BufferedReader.readLine() 等结束标记；命令在虚拟屏上卡住时主线程被堵死
     * 10 秒触发 ANR（真机 ANR trace 已确认堆栈），sShellLock 还会被永久占住，
     * 导致之后所有 root 命令排队、整个应用假死。
     */
    @Synchronized
    override fun injectKey(keyCode: Int, down: Boolean, modifiers: Int): Boolean {
        val id = displayId
        if (id <= 0) return false
        // HOME / 手柄 Mode 键在系统层就被 PhoneWindowManager 吃掉（直接启动 home
        // activity），Activity 侧永远收不到 dispatchKeyEvent。旧实现在抬起时重新
        // startDesktop，等于又拉起一个桌面实例，沧海系统 看起来被顶掉、露出系统桌面。
        // 现在改为带 desktop_page=launchpad 启动桌面：singleTask 走 onNewIntent，
        // 桌面自己前置并打开启动台；官方桌面模式下 HOME 本来就该回官方桌面，交给系统。
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (homeType == 1) {
                if (!down) postShell { startDesktop(id, "launchpad") }
                return true
            }
        }
        // 客户端右键（KEYCODE_BACK + MOUSE_RIGHT_FLAG）已在主进程统一语义层
        // SunshineRuntimeManager.translateRemoteKey 里翻译成桌面动作，这里只做纯注入，
        // 不再各补一次长按，三种权限链路只保留一份右键逻辑。
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        return RootInputBridge.injectKey(context, id, action, keyCode, modifiers,
            InputDevice.SOURCE_KEYBOARD)
    }

    /** 一次完整按键 = DOWN + UP 两帧，仍然零 shell、零等待。 */
    private fun key(code: Int): Boolean {        val id = displayId
        if (id <= 0) return false
        val down = RootInputBridge.injectKey(context, id, KeyEvent.ACTION_DOWN, code, 0,
            InputDevice.SOURCE_KEYBOARD)
        val up = RootInputBridge.injectKey(context, id, KeyEvent.ACTION_UP, code, 0,
            InputDevice.SOURCE_KEYBOARD)
        return down && up
    }

    // ======================= 状态 =======================

    @Synchronized
    override fun snapshot(): String = JSONObject()
        .put("display_id", displayId)
        .put("protocol_version", PROTOCOL_VERSION)
        .put("build_code", BuildConfig.VERSION_CODE)
        .put("launcher", launcher)
        .put("mode", mode)
        .put("desktop_controls", false)
        .put("retained_display_id", displayId)
        .put("requested_refresh_rate", requestedRefreshRate)
        .put("actual_refresh_rate", 60.0)
        .put("refresh_rate_request_supported", false)
        // 明确关掉所有三星 DeX 分支：上层据此跳过 prepareLocalDex 的 180s 人工确认等待，
        // 也不会误判成「系统 DeX 镜像」而检查 displayId==0。
        .put("system_dex_mirror", false)
        .put("dex_requested", false)
        .put("dex_active", false)
        .put("local_dex_pending", false)
        .put("local_dex_ready", false)
        .put("local_dex_stage", "idle")
        .put("local_dex_error", "")
        .put("dex_route_detail", "")
        .put("panel_off", false)
        .put("secondary_home", "")
        .put("native_dex_width", 0)
        .put("native_dex_height", 0)
        .put("default_dex_width", width)
        .put("default_dex_height", height)
        .put("detail", detail)
        .toString()

    /** 供日志与诊断使用，不参与 AIDL。 */
    fun currentDisplayId(): Int = synchronized(this) { displayId }
}
