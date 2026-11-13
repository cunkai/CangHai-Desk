package com.alkaidlab.sunshine.service

import android.content.*
import android.os.*
import android.util.Log
import android.view.*
import com.aosen.shizukudex.PrivilegeRoute
import com.aosen.shizukudex.RootCmd
import com.aosen.shizukudex.RootInputBridge
import com.aosen.shizukudex.ShizukuCmd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class VirtualScreenState(val active: Boolean = false, val busy: Boolean = false, val displayId: Int = -1,
    val width: Int = 0, val height: Int = 0, val mode: String = "desktop", val launcher: String = "", val error: String? = null,
    val requestedRefreshRate: Int = 0, val actualRefreshRate: Float = 0f, val refreshRateRequestSupported: Boolean = false,
    val dexRequested: Boolean = false, val dexActive: Boolean = false, val detail: String = "", val systemDexMirror: Boolean = false,
    val mainPanelOff: Boolean = false, val desktopControls: Boolean = false, val retainedDisplayId: Int = -1)

class VirtualScreenClient(private val context: Context, private val disconnected: () -> Unit) {
    private companion object { const val TAG = "AosenVirtualScreen" }
    private var remote: ISunshineDisplayController? = null
    /** 纯 Root 通道的**本地** display controller，与 remote 指向同一实例。 */
    private var rootController: RootDisplayController? = null
    private var waiting: CompletableDeferred<ISunshineDisplayController>? = null
    /** 当前会话的虚拟屏 id，release 时用它决定外设回退到哪块屏。 */
    private var currentDisplayId = -1
    private val owner = Binder()
    private val installVersion = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, VirtualDisplayUserService::class.java.name))
        .daemon(false).processNameSuffix("virtualdisplay").tag("sunshine-virtualdisplay")
        .version((installVersion xor (installVersion ushr 32)).toInt())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val pending = waiting ?: return
            if (!pending.isActive) return
            val controller = ISunshineDisplayController.Stub.asInterface(service)
            remote = controller
            service.linkToDeath({ remote = null; disconnected() }, 0)
            pending.complete(controller)
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; disconnected() }
    }

    /**
     * 两条完全独立的通道：
     *  - 有 Shizuku 授权 → 原来的 shell UserService（VirtualDisplayUserService）；
     *  - 只有 Root      → 本地 RootDisplayController，root UID 建屏，完全不碰 Shizuku。
     *
     * 修复点：旧版无条件 check(PrivilegedShell.available())，纯 Root 设备在这里直接
     * 抛「创建虚拟屏需要 Shizuku 授权」，编码器 Surface 从未绑定 VirtualDisplay，
     * 一帧都产不出来，Moonlight 客户端握手后立刻掐断连接。
     *
     * 选路说明：这里保留「Shizuku 已授权优先」，是为了不把当前唯一在真机上验证过
     * 的通道替换掉；纯 Root 用户则自动落到新通道，两条路互不依赖。特权门面
     * PrivilegeRoute 仍是 Root 优先，RootDisplayController 内部建屏也走它。
     */
    suspend fun connect(): ISunshineDisplayController {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (PrivilegedShell.available()) return connectShizuku()

        val rootReady = withContext(Dispatchers.IO) {
            try { RootCmd.probe(context) } catch (_: Throwable) { false }
        }
        check(rootReady && PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            "创建虚拟屏需要 Root 或 Shizuku 任一授权"
        }
        // 预热常驻 root 桥：让它的 Binder 句柄在建屏之前就经 :bridge → Provider
        // 落到主进程。warmUp 自己起后台线程，不占用当前协程/主线程。
        RootInputBridge.warmUp(context)
        val local = rootController ?: RootDisplayController(context).also { rootController = it }
        remote = local
        check(org.json.JSONObject(local.snapshot()).optInt("protocol_version") == 11) {
            "显示服务版本过旧，请重新绑定"
        }
        local.attachOwner(owner)
        SunshineLogManager.log("ROOT_DISPLAY", "纯 Root 通道接管 Moonlight 虚拟屏（无需 Shizuku）")
        return local
    }

    private suspend fun connectShizuku(): ISunshineDisplayController {
        ShizukuServiceBootstrap.prepare(context)
        val promise = CompletableDeferred<ISunshineDisplayController>()
        waiting = promise
        Shizuku.bindUserService(args, connection)
        return try {
            val service = withTimeout(8000) { promise.await() }
            check(org.json.JSONObject(service.snapshot()).optInt("protocol_version") == 11) { "显示服务版本过旧，请重新绑定" }
            service.attachOwner(owner)
            service
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            ShizukuServiceBootstrap.invalidate()
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
            remote = null
            throw IllegalStateException("Shizuku 显示服务未能启动。请打开 Shizuku／Stellar 管理器，取消其休眠或后台限制，返回 Sunshine 后重试；应用授权无需重置。", error)
        } catch (error: Exception) {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
            remote = null
            throw error
        } finally { waiting = null }
    }

    suspend fun create(width: Int, height: Int, density: Int, surface: Surface, mode: String, fps: Int, systemDesktop: Boolean, highRefresh: Boolean): Int {
        val id = connect().createDisplay(width, height, density, surface, mode, owner, fps, systemDesktop, highRefresh)
        if (id > 0) currentDisplayId = id
        // 建屏后立刻把 IME 策略钉成 ON_SCREEN：非主屏默认是 FALLBACK_DISPLAY，
        // 输入法会被送回手机，候选框出现在手机上、有时两边都不显示。
        // 两种通道都覆盖（Shizuku binder / 常驻 root 桥），一次串流只调一次。
        if (id > 0) {
            try {
                ShizukuCmd.applyOnScreenImePolicy(id)
            } catch (t: Throwable) {
                Log.w(TAG, "设置虚拟屏 IME 策略失败: ${t.message}")
            }
            // 外设（USB 鼠标 / 蓝牙手柄 / 外接键盘）必须显式绑到本屏。
            // 不绑的话系统把它们留在「真 HDMI 外接屏」或上一次会话遗留的旧屏上，
            // 虚拟屏收不到任何键鼠/手柄事件 —— 表现为手柄没反应、输入监视一条都抓不到。
            // 一次性事务，不在输入热路径上，无性能影响。
            try {
                ShizukuCmd.bindAllExternalInputToDisplay(id, true)
                Log.i(TAG, "已请求把外接输入绑定到 display=$id")
            } catch (t: Throwable) {
                Log.w(TAG, "绑定外接输入失败: ${t.message}")
            }
        }
        return id
    }
    suspend fun blankMain(): Boolean = connect().blankMainScreen(owner)
    suspend fun prepareLocalDex(): Boolean = connect().prepareLocalDex(owner)
    suspend fun configureDesktop(width: Int, height: Int, samsungDex: Boolean, fullscreen: Boolean): String =
        connect().configureDesktop(width, height, samsungDex, owner, fullscreen)
    suspend fun reconfigureDisplay(width: Int, height: Int, surface: Surface, fullscreen: Boolean): String =
        connect().reconfigureDisplay(width, height, surface, fullscreen)
    suspend fun setMainPanelOff(off: Boolean): Boolean = connect().setMainPanelOff(off)
    fun close() { remote?.closeDisplay() }
    fun snapshot(): String = remote?.snapshot().orEmpty()
    fun launch(component: String): Boolean = remote?.launchApp(component) == true
    fun tasks(): String = remote?.desktopTasks() ?: "[]"
    fun action(action: String, taskId: Int = -1): Boolean = remote?.desktopAction(action, taskId) == true
    fun motion(event: MotionEvent): Boolean = remote?.injectMotion(event) == true
    /** 沧海集成：0=官方桌面（三星 DeX 副屏桌面），1=沧海桌面（CastHomeActivity）。 */
    fun setHomeType(type: Int) {
        // Root 通道下 remote 就是 rootController，先判 root 避免重复下发。
        val rc = rootController
        if (rc != null) {
            try { rc.setHomeType(type) } catch (_: Exception) {}
            return
        }
        try { remote?.setHomeType(type) } catch (_: Exception) {}
    }
    fun key(code: Int, down: Boolean, modifiers: Int): Boolean = remote?.injectKey(code, down, modifiers) == true
    fun release() {
        try { close() } catch (_: Exception) {}
        // Root 本地控制器的 destroy 就是 closeDisplay，上面已经做过；只有远程
        // Shizuku UserService 才需要额外的 destroy 事务（它会退出 shell 进程）。
        try { if (rootController == null) remote?.destroy() } catch (_: Exception) {}
        // 屏幕销毁后外设关联仍指着这个不存在的 display，手柄/鼠标会卡死在这一屏上，
        // 手机和下一段会话都收不到输入。交给 ShizukuCmd 决定回退到哪块屏还是解绑。
        val releasedId = currentDisplayId
        currentDisplayId = -1
        try {
            if (releasedId > 0) ShizukuCmd.onDisplayReleased(releasedId)
            else ShizukuCmd.unbindAllExternalInputFromDisplaySync()
        } catch (_: Exception) {}
        try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
        rootController = null
        remote = null
    }
}
