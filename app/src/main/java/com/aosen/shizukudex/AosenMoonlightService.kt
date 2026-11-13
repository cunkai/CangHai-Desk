package com.aosen.shizukudex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.alkaidlab.sunshine.controller.ControllerAdapter
import com.alkaidlab.sunshine.service.BootCaptureBootstrap
import com.alkaidlab.sunshine.service.SunshineEngineClient
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * 沧海桌面 · Moonlight 串流主机（基于 Sunshine for Android 引擎）。
 *
 * 与原版 SunshineService 的差异：
 *  - 虚拟屏启动的不是 Sunshine 桌面，而是沧海桌面 CastHomeActivity
 *    （VirtualDisplayUserService.desktopComponent 已改指沧海桌面）；
 *  - Moonlight 连接成功后自动创建虚拟屏并拉起沧海桌面，无需手动点按；
 *  - 通知入口指向沧海桌面主页。
 *
 * 流程：主页请求录屏授权 → start(resultData) → 引擎 :engine 进程起 GameStream
 * 服务 → Moonlight 客户端配对并请求 Desktop 应用 → streamStarted(编码 Surface)
 * → Root(root UID 常驻桥) 或 Shizuku(shell UserService) 建虚拟屏并绑定编码 Surface
 * → 沧海桌面渲染其上 → Moonlight 全平台客户端。
 * 两条通道完全独立、Root 优先：只有 Root 也必须有画面，不再强依赖 Shizuku 授权。
 */
class AosenMoonlightService : Service() {

    companion object {
        const val ACTION_START = "com.aosen.shizukudex.moonlight.START"
        const val ACTION_STOP = "com.aosen.shizukudex.moonlight.STOP"
        const val ACTION_EXIT_DESKTOP = "com.aosen.shizukudex.moonlight.EXIT_DESKTOP"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "sunshine_service_channel" // 与 :engine 进程通知共用
        private const val NOTIFY_ID = 1001

        @Volatile var isRunning = false
            private set

        @JvmStatic fun isHostRunning(): Boolean = isRunning

        @JvmStatic
        fun start(context: Context, resultData: Intent? = null) {
            val intent = Intent(context, AosenMoonlightService::class.java).apply {
                action = ACTION_START
                if (resultData != null) putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, AosenMoonlightService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineClient by lazy { SunshineEngineClient(this) }
    private var autoScreenScheduled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        com.aosen.shizukudex.ShizukuCmd.setAppContext(applicationContext)
        // 纯 Root 通道要在 Moonlight 握手前把常驻 root 桥拉起来：它会经
        // :bridge 进程 → RootBridgeProvider 把 Binder 句柄送回主进程，建屏时
        // 才能以 root UID 创建带系统装饰的虚拟屏。warmUp 在后台线程，幂等。
        try { RootInputBridge.warmUp(this) } catch (_: Throwable) {}
        val runtime = SunshineRuntimeManager.getInstance()
        runtime.initialize(this)
        runtime.engineClient = engineClient
        runtime.captureRecovery = BootCaptureBootstrap(this, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_EXIT_DESKTOP -> {
                serviceScope.launch(Dispatchers.Main) { SunshineRuntimeManager.getInstance().exitVirtualScreen() }
                return START_NOT_STICKY
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                // 沧海虚拟屏串流不依赖录屏授权（画面走 Root / Shizuku 虚拟屏直连编码器），
                // 一键直开，全程无系统弹窗；音频内录缺失授权时自动降级为纯视频。
                // startForeground(id, n, type) 是 API 29 才加的方法。API 28 及以下调它不会抛
                // 编译错误，而是运行时 NoSuchMethodError 直接把进程干掉（老机器一启动就闪退）。
                val hostNotice = buildNotification("沧海串流主机已就绪")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFY_ID, hostNotice,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFY_ID, hostNotice)
                }
                isRunning = true
                ControllerAdapter.start(this)
                initAndStartEngine()
            }
        }
        return START_STICKY
    }

    /** 证书、应用目录、默认配置 + 启动原生引擎（幂等）。 */
    private fun initAndStartEngine() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 原生内核已按 android-28 重建（旧版用 android-29 编出来的 TLSDESC 重定位
                // 在老 linker 上 dlopen 必失败）。这里不再按系统版本一刀切，直接尝试；
                // 万一某个机型仍加载不了，NativeServer 只会标记不可用并抛可捕获异常，
                // 不会再打死 :engine 进程。
                ensureDefaultFiles()
                val serverName = "${Build.MANUFACTURER}-${Build.MODEL}"
                if (!engineStarted) {
                    engineStarted = true
                    engineClient.start(serverName,
                        File(filesDir, "cacert.pem").absolutePath,
                        File(filesDir, "cakey.pem").absolutePath,
                        File(filesDir, "sunshine_state.json").absolutePath)
                    SunshineLogManager.log("ENGINE", "沧海串流引擎已请求启动：$serverName")
                } else {
                    // 引擎已在运行：配置变更后重放
                    SunshineRuntimeManager.getInstance().reloadConfig()
                }
                scheduleAutoVirtualScreen()
            } catch (e: Throwable) {
                // UnsatisfiedLinkError / NoClassDefFoundError 都是 Error，catch Exception 兜不住。
                SunshineLogManager.log("INIT_ERR", "引擎启动失败: ${e.message}")
            }
        }
    }

    @Volatile private var engineStarted = false

    /**
     * Moonlight 连接（streamStarted 由引擎回调）后，虚拟屏由当前通道的 display
     * controller 在 createDisplay 时自动拉起沧海/官方桌面：
     *  - Shizuku 授权 → VirtualDisplayUserService（shell 进程）
     *  - 纯 Root      → RootDisplayController（主进程本地 + root UID 建屏）
     * 这里兜底：串流建立后若虚拟屏未建，主动创建一次。
     */
    private fun scheduleAutoVirtualScreen() {
        if (autoScreenScheduled) return
        autoScreenScheduled = true
        serviceScope.launch(Dispatchers.Main) {
            try {
                val runtime = SunshineRuntimeManager.getInstance()
                runtime.isStreaming.collect { streaming ->
                    if (streaming && !runtime.virtualScreenState.value.active && !runtime.virtualScreenState.value.busy) {
                        delay(800) // 等引擎会话稳定
                        val result = runtime.createVirtualScreen()
                        SunshineLogManager.log("AUTO_SCREEN", "自动创建虚拟屏: ${result.message}")
                    }
                }
            } catch (_: Exception) {
            } finally { autoScreenScheduled = false }
        }
    }

    private fun ensureDefaultFiles() {
        // 证书
        for (name in listOf("cacert.pem", "cakey.pem")) {
            val f = File(filesDir, name)
            if (!f.exists()) assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        // 应用图标：Moonlight 客户端经 Sunshine /appasset 端点读取 image-path 指向的文件
        val boxart = File(filesDir, "aosen_boxart.png")
        if (!boxart.exists()) {
            try {
                assets.open("aosen_boxart.png").use { input -> boxart.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) { }
        }
        // 应用目录：Moonlight 客户端里显示为「沧海系统」
        val apps = File(filesDir, "apps.json")
        if (!apps.exists()) {
            apps.writeText("""{"apps":[{"id":"1","name":"沧海系统","image-path":"${boxart.absolutePath}","cmd":"","singleApp":false}],"nextId":2}""")
        }
        // 串流配置：虚拟屏模式 + 沧海桌面 + 默认码率
        val conf = File(filesDir, "sunshine.conf")
        if (!conf.exists()) conf.writeText("""
            # 沧海桌面 Moonlight 串流配置
            virtual_screen_mode = desktop
            virtual_screen_density = 240
            virtual_screen_desktop = sunshine
            max_bitrate = 50000
            min_bitrate = 5000
            hevc_mode = enabled
            auto_rotate = true
            auto_scale = true
        """.trimIndent())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val c = NotificationChannel(CHANNEL_ID, "沧海串流主机", NotificationManager.IMPORTANCE_LOW)
                c.description = "Moonlight 串流服务运行状态"
                nm.createNotificationChannel(c)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val exitPi = PendingIntent.getService(this, 2,
            Intent(this, AosenMoonlightService::class.java).setAction(ACTION_EXIT_DESKTOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 3,
            Intent(this, AosenMoonlightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("沧海串流主机")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "退出桌面", exitPi)
            .addAction(0, "停止串流", stopPi)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        ControllerAdapter.stop()
        engineClient.stop()
        val runtime = SunshineRuntimeManager.getInstance()
        runtime.engineClient = null
        runtime.captureRecovery = null
        runtime.releaseAll()
        engineStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
