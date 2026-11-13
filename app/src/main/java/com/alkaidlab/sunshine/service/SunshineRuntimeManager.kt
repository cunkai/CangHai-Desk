package com.alkaidlab.sunshine.service

import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.view.Display
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.alkaidlab.sunshine.data.AudioDiagnostics
import com.alkaidlab.sunshine.data.DiagnosticsResponse
import com.alkaidlab.sunshine.data.PerfResponse
import com.alkaidlab.sunshine.data.RuntimeSession
import com.aosen.shizukudex.LocalMoonlightDisplay
import com.aosen.shizukudex.StreamTarget
import com.aosen.shizukudex.PrivilegeRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SunshineRuntimeManager private constructor() {

    companion object {
        /** Moonlight 鼠标右键包经 KEYCODE_BACK 传入时的私有标记，避免把手柄/系统返回键也当右键。 */
        const val MOUSE_RIGHT_FLAG = 1 shl 28

        @Volatile
        private var instance: SunshineRuntimeManager? = null

        @JvmStatic
        fun getInstance(): SunshineRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: SunshineRuntimeManager().also { instance = it }
            }
        }
    }

    private var context: Context? = null
    @Volatile private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile var engineClient: SunshineEngineClient? = null
    private val frameDispatcher = SunshineFrameDispatcher()
    private val captureHandler = Handler(Looper.getMainLooper())
    private val _virtualScreenState = MutableStateFlow(VirtualScreenState())
    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()
    fun setEngineReady(ready: Boolean) { _engineReady.value = ready }
    val virtualScreenState: StateFlow<VirtualScreenState> = _virtualScreenState.asStateFlow()
    private var virtualScreenClient: VirtualScreenClient? = null
    @Volatile private var preparingLocalDex = false
    val sourceDisplayId: Int get() = _virtualScreenState.value.displayId.takeIf { _virtualScreenState.value.active } ?: Display.DEFAULT_DISPLAY
    val virtualSourceFrameLimit: Int get() = frameDispatcher.virtualSourceFrameLimit
    private var captureDisplayManager: DisplayManager? = null
    private val _fullscreenBusy = MutableStateFlow(false)
    private val fullscreenScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    val fullscreenBusy: StateFlow<Boolean> = _fullscreenBusy.asStateFlow()
    private val _streamDisplayMode = MutableStateFlow(StreamDisplayMode.DEFAULT)
    val streamDisplayMode: StateFlow<StreamDisplayMode> = _streamDisplayMode.asStateFlow()
    private val displayModeMutex = Mutex()
    private val displayModeChanges = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var fullscreenOwner: Long? = null
    private var appliedFullscreen: List<Long>? = null
    private val captureDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == sourceDisplayId && _virtualScreenState.value.active && !_virtualScreenState.value.busy)
                fullscreenScope.launch(Dispatchers.Main) { exitVirtualScreen() }
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) syncCaptureSize()
            val current = _virtualScreenState.value
            if (current.active && current.mode == "desktop" && !noPrivilege() && current.displayId == displayId) {
                captureDisplayManager?.getDisplay(displayId)?.let { display ->
                    if (current.systemDexMirror && display.state != Display.STATE_ON && !current.busy) {
                        if (current.displayId == Display.DEFAULT_DISPLAY) {
                            // Closing a tablet cover powers off display 0; it does not
                            // remove the native desktop. Keep logical rendering awake
                            // and blank only the physical panel instead of exiting DeX.
                            SunshineLogManager.log("VIRTUAL_POWER", "Primary DeX slept; recovering with physical panel blanked")
                            fullscreenScope.launch(Dispatchers.Main) { setVirtualScreenPanelOff(true) }
                        } else {
                            SunshineLogManager.log("VIRTUAL_EXIT", "Native secondary display powered off")
                            fullscreenScope.launch(Dispatchers.Main) { exitVirtualScreen() }
                        }
                        return
                    }
                    if (_virtualScreenState.compareAndSet(current, current.copy(actualRefreshRate = display.refreshRate)) &&
                        current.actualRefreshRate != display.refreshRate) {
                        frameDispatcher.setVirtualSource(true, display.refreshRate)
                        engineClient?.configureCanvas(current.width, current.height, false, false)
                    }
                }
            }
        }
    }

    init {
        fullscreenScope.launch {
            for (ignored in displayModeChanges) {
                val result = applyStreamDisplayMode()
                if (!result.applied) SunshineLogManager.log("DISPLAY_MODE", result.message)
            }
        }
        frameDispatcher.setOnSourceCadenceRaisedListener { generation ->
            captureHandler.post {
                val state = _virtualScreenState.value
                if (generation == frameDispatcher.sourceGeneration && state.active && state.mode == "desktop") {
                    engineClient?.configureCanvas(state.width, state.height, false, false)
                    SunshineLogManager.log("SOURCE_FPS", "Producer timestamps demonstrate ${frameDispatcher.virtualSourceFrameLimit}fps; display reports ${state.actualRefreshRate}Hz")
                }
            }
        }
    }

    private fun displayCaptureSize(fallbackWidth: Int, fallbackHeight: Int): IntArray {
        _virtualScreenState.value.takeIf { it.active && it.mode == "desktop" }?.let { return intArrayOf(it.width, it.height) }
        val primarySize = Point()
        @Suppress("DEPRECATION") captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(primarySize)
        if (primarySize.x > 0 && primarySize.y > 0) return intArrayOf(primarySize.x, primarySize.y)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context?.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0)
                return intArrayOf(bounds.width(), bounds.height())
        }
        val size = Point()
        captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        return if (size.x > 0 && size.y > 0) intArrayOf(size.x, size.y)
        else intArrayOf(fallbackWidth.coerceAtLeast(1), fallbackHeight.coerceAtLeast(1))
    }

    /** A low-resolution or differently oriented first client must never shrink later clients. */
    private fun syncCaptureSize(): Boolean {
        if ((_virtualScreenState.value.active && _virtualScreenState.value.mode == "desktop") || _virtualScreenState.value.busy) return true
        val display = virtualDisplay ?: return false
        if (!frameDispatcher.isRunning) return false
        frameDispatcher.sourceRotation = captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        val size = displayCaptureSize(frameDispatcher.sourceWidth, frameDispatcher.sourceHeight)
        if (size[0] == frameDispatcher.sourceWidth && size[1] == frameDispatcher.sourceHeight) {
            engineClient?.configureCanvas(size[0], size[1], autoRotateEnabled, rotationCompatEnabled)
            return true
        }
        val oldWidth = frameDispatcher.sourceWidth
        val oldHeight = frameDispatcher.sourceHeight
        try {
            display.surface = null
            val resizedSurface = frameDispatcher.resizeCaptureSync(size[0], size[1])
                ?: throw IllegalStateException("Capture surface resize failed")
            display.resize(size[0], size[1], context?.resources?.displayMetrics?.densityDpi ?: 300)
            display.surface = resizedSurface
            engineClient?.configureCanvas(size[0], size[1], autoRotateEnabled, rotationCompatEnabled)
            SunshineLogManager.log("VD", "Capture resized to ${size[0]}x${size[1]} (display pixels)")
            return true
        } catch (e: Exception) {
            try {
                display.resize(oldWidth, oldHeight, context?.resources?.displayMetrics?.densityDpi ?: 300)
                display.surface = frameDispatcher.resizeCaptureSync(oldWidth, oldHeight)
            } catch (_: Exception) {}
            SunshineLogManager.log("VD_ERR", "Capture resize failed: ${e.message}")
            return false
        }
    }
    private var appSessionGuard: AppSessionGuard? = null
    private fun ensureAppGuard(): AppSessionGuard? {
        if (appSessionGuard == null) context?.let { ctx ->
            appSessionGuard = AppSessionGuard(ctx) { threadId ->
                val video = engineClient?.videoSessions()?.firstOrNull { it.threadId == threadId }
                video != null && engineClient?.controlVideo(video.sessionId, 3) == true
            }
        }
        return appSessionGuard
    }
    fun nativeAppsJson(): String { return try {
        val ctx = context ?: return "{\"apps\":[]}"
        com.google.gson.Gson().toJson(AppCatalog.read(java.io.File(ctx.filesDir, "apps.json")))
    } catch (_: Exception) { "{\"apps\":[]}" } }
    @Volatile var captureRecovery: BootCaptureBootstrap? = null

    fun prepareApp(appId: Int): Boolean { return try {
        val ctx = context ?: return false
        // 沧海集成：虚拟屏串流不需要 MediaProjection——画面来自 Shizuku 虚拟屏直连编码
        // Surface，原 Sunshine 的录屏授权前置检查对「Desktop」应用不再适用。
        val app = AppCatalog.read(java.io.File(ctx.filesDir, "apps.json")).apps.firstOrNull { it.id?.toIntOrNull() == appId } ?: return false
        if (app.singleApp && !PrivilegedShell.available()) return false
        app.cmd.isNullOrBlank() || launchAppByCommand(app.cmd)
    } catch (_: Exception) { false } }


    // Streaming State
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _encoderError = MutableStateFlow<String?>(null)
    val encoderError: StateFlow<String?> = _encoderError.asStateFlow()
    fun reportEncoderError(error: String) { _encoderError.value = error; SunshineLogManager.log("ENCODER_ERR", error) }
    fun dismissEncoderError() { _encoderError.value = null }
    @Volatile var encoderH264: String = "auto"
        private set
    @Volatile var encoderHevc: String = "auto"
        private set

    private val pinPairing = PinPairingController(submitToEngine = { pin -> engineClient?.submitPin(pin) == true })
    val pinRequested: StateFlow<String?> = pinPairing.pinRequested

    private val _activeSessionsFlow = MutableStateFlow<List<RuntimeSession>>(emptyList())
    val activeSessionsFlow: StateFlow<List<RuntimeSession>> = _activeSessionsFlow.asStateFlow()

    private val _perfFlow = MutableStateFlow(PerfResponse())
    val perfFlow: StateFlow<PerfResponse> = _perfFlow.asStateFlow()

    private val activeSessions = CopyOnWriteArrayList<RuntimeSession>()
    private var streamStartTime: Long = 0

    @Volatile
    private var lastDiscoveredClient: String? = null

    // Telemetry & Config State
    @Volatile private var lastFps: Double = 0.0
    @Volatile private var lastEncodeTimeMs: Double = 0.0
    @Volatile private var lastNetworkLatencyMs: Double? = null
    @Volatile private var lastNetworkProbeIp: String? = null
    @Volatile private var lastNetworkSampleMs = 0L
    @Volatile private var lastBitrate: Int = 10000
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    private val lastCodec = "未知（原生未上报）"
    private var clientUuid: String = ""

    @Volatile var hevcEnabled: Boolean = true
        private set
    @Volatile var rotationCompatEnabled: Boolean = false
    @Volatile var autoRotateEnabled: Boolean = true
        private set
    @Volatile var autoScaleEnabled: Boolean = true
        private set
    @Volatile var configuredMaxBitrate: Int = 50000
        private set
    @Volatile var configuredMinBitrate: Int = 5000
        private set

    private val _streamingSettings = MutableStateFlow(StreamingSettings())
    val streamingSettings: StateFlow<StreamingSettings> = _streamingSettings.asStateFlow()

    private var telemetryThread: Thread? = null
    @Volatile private var telemetryEpoch = 0L
    private var prevTxBytes: Long = -1L
    private var prevTxTimeMs: Long = 0L

    // Shizuku Input injection reflection
    private var remoteWake: RemoteInputWakeManager? = null
    fun onRemoteInput() { if (!_virtualScreenState.value.active && !_virtualScreenState.value.busy) remoteWake?.onInput(_isStreaming.value) }
    fun onVideoFrameProgress(threadId: Long, presentationTimeUs: Long, released: Boolean) {
        frameDispatcher.frameProgress(threadId, presentationTimeUs, released)
    }
    private var inputManager: Any? = null
    private var injectInputMethod: Method? = null

    fun initialize(appContext: Context) {
        this.context = appContext.applicationContext
        _streamDisplayMode.value = StreamDisplayPreferences(appContext).read()
        if (captureDisplayManager == null) {
            captureDisplayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            captureDisplayManager?.registerDisplayListener(captureDisplayListener, captureHandler)
        }
        remoteWake = RemoteInputWakeManager(appContext.applicationContext, ::injectWakeKey)
        com.alkaidlab.sunshine.engine.InputBroker.setContext(appContext)
        reloadConfig(this.context)
        initShizukuInput()
        frameDispatcher.setOnSurfaceEvictedListener { _ ->
            // Rendering failure is not proof of native disconnect. Preserve lifecycle identities
            // until the engine reports stop, so a bad surface cannot trigger a global restart.
            SunshineLogManager.log("STREAM", "Client surface evicted; awaiting native disconnect")
        }
    }

    @Synchronized
    fun reloadConfig(ctx: Context? = this.context) {
        val c = ctx ?: return
        try {
            val confFile = java.io.File(c.filesDir, "sunshine.conf")
            if (confFile.exists()) {
                val props = mutableMapOf<String, String>()
                confFile.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#") && t.contains("=")) {
                        val parts = t.split("=", limit = 2)
                        props[parts[0].trim()] = parts[1].trim()
                    }
                }
                encoderH264 = props["encoder_h264"] ?: "auto"
                encoderHevc = props["encoder_hevc"] ?: "auto"
                engineClient?.configureEncoders(encoderH264, encoderHevc)
                hevcEnabled = (props["hevc_mode"] ?: "enabled") != "disabled"
                autoRotateEnabled = (props["auto_rotate"] ?: "true") != "false"
                rotationCompatEnabled = props["rotation_compat"] == "true"
                val capture = if (frameDispatcher.isRunning) intArrayOf(frameDispatcher.sourceWidth, frameDispatcher.sourceHeight)
                    else displayCaptureSize(1, 1)
                engineClient?.configureCanvas(capture[0], capture[1], autoRotateEnabled, rotationCompatEnabled)
                autoScaleEnabled = (props["auto_scale"] ?: "true") != "false"
                configuredMaxBitrate = (props["max_bitrate"]?.toIntOrNull() ?: 50000).coerceIn(1000, 150000)
                configuredMinBitrate = (props["min_bitrate"]?.toIntOrNull() ?: 5000).coerceIn(1000, configuredMaxBitrate)
                val settings = StreamingSettings.from(props)
                _streamingSettings.value = settings
                frameDispatcher.configure(settings)
                engineClient?.configureVideo(configuredMinBitrate, configuredMaxBitrate, settings.maxFps)
                if (audioBufferMs != settings.audioBufferMs) {
                    audioBuffer = PcmAudioBuffer(maxOf(settings.audioBufferMs * 96, audioPacketFloats))
                    audioBufferMs = settings.audioBufferMs
                }
            }
        } catch (e: Exception) {
            SunshineLogManager.log("CONFIG_WARN", "reloadConfig error: ${e.message}")
        }
    }

    fun launchAppByCommand(cmd: String): Boolean {
        val ctx = context ?: return false
        val pkg = cmd.trim()
        if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return false
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            val component = intent.component ?: return false
            if (PrivilegedShell.available()) {
                val output = PrivilegedShell.run(arrayOf("am", "start", "-n", component.flattenToString()), 3000) ?: return false
                if (output.contains("Error:", true) || output.contains("Exception", true)) return false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
            SunshineLogManager.log("APP_LAUNCH", "Launched selected application")
            true
        } catch (e: Exception) {
            SunshineLogManager.log("APP_ERR", "Application could not be opened: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun startTelemetryLoop() {
        if (telemetryThread?.isAlive == true) return
        prevTxBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        prevTxTimeMs = SystemClock.elapsedRealtime()

        val epoch = ++telemetryEpoch
        telemetryThread = Thread({
            val timeRegex = Regex("time=([0-9.]+)")
            while (_isStreaming.value && telemetryEpoch == epoch && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    if (!_isStreaming.value || telemetryEpoch != epoch) break

                    // 1. Sample real-time FPS & GPU->Hardware Encoder surface latency
                    val (measuredFps, measuredEncodeMs) = frameDispatcher.sampleTelemetry()
                    lastFps = measuredFps
                    lastEncodeTimeMs = measuredEncodeMs

                    // 2. Sample real-time process network TX bitrate via TrafficStats
                    val nowTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val nowMs = SystemClock.elapsedRealtime()
                    if (prevTxBytes >= 0 && nowTx >= prevTxBytes && nowMs > prevTxTimeMs) {
                        val deltaBytes = nowTx - prevTxBytes
                        val elapsedSec = (nowMs - prevTxTimeMs) / 1000.0
                        val kbps = ((deltaBytes * 8.0) / elapsedSec / 1000.0).toInt()
                        lastBitrate = kbps
                    }
                    prevTxBytes = nowTx
                    prevTxTimeMs = nowMs

                    // 3. Auto-detect connected peer IPs from /proc/net/tcp & /proc/net/tcp6
                    val peerIps = detectConnectedPeerIps()
                    if (peerIps.isNotEmpty()) {
                        lastDiscoveredClient = peerIps.last()
                    }

                    // ICMP is a peer probe, not a video transport/encode latency measurement.
                    val probeIp = activeSessions.lastOrNull()?.clientIp?.takeIf { it != "127.0.0.1" }
                        ?: peerIps.lastOrNull()
                    if (probeIp != null && probeIp != "127.0.0.1") {
                        val rtt = measurePingRttMs(probeIp, timeRegex)
                        if (rtt != null && rtt > 0.0) {
                            lastNetworkLatencyMs = rtt
                            lastNetworkProbeIp = probeIp
                            lastNetworkSampleMs = SystemClock.elapsedRealtime()
                        }
                    }

                    if (telemetryEpoch != epoch || Thread.currentThread().isInterrupted) break
                    val nativeVideo = engineClient?.videoSessions().orEmpty().associateBy { it.threadId }
                    synchronized(this@SunshineRuntimeManager) {
                        if (activeSessions.isNotEmpty()) {
                            var changed = false
                            val updated = activeSessions.mapIndexed { idx, s ->
                                val resolvedIp = if (s.clientIp == "127.0.0.1" && peerIps.isNotEmpty()) {
                                    peerIps.getOrElse(idx) { peerIps.last() }
                                } else s.clientIp
                                val resolvedName = if (s.clientName.startsWith("Moonlight Client") && s.clientUuid.isNullOrBlank() && resolvedIp != "127.0.0.1") {
                                    "Moonlight ($resolvedIp)"
                                } else s.clientName
                                val video = nativeVideo[s.videoThreadId]
                                if (s.clientIp != resolvedIp || s.clientName != resolvedName ||
                                    (video != null && (s.sessionId != video.sessionId || s.bitrate != video.appliedBitrateKbps || s.codec != video.codec))) {
                                    changed = true
                                    s.copy(clientName = resolvedName, clientIp = resolvedIp,
                                        sessionId = video?.sessionId ?: s.sessionId,
                                        bitrate = video?.appliedBitrateKbps ?: s.bitrate,
                                        codec = video?.codec ?: s.codec)
                                } else s
                            }
                            if (changed) {
                                activeSessions.clear()
                                activeSessions.addAll(updated)
                                _activeSessionsFlow.value = activeSessions.toList()
                            }
                        }
                    }

                    // 6. Keep physical display orientation updated if auto_rotate is enabled
                    if (autoRotateEnabled) {
                        com.alkaidlab.sunshine.engine.InputBroker.updateDisplayDimensions()
                    }

                    // 7. Emit fresh PerfResponse to StateFlow
                    _perfFlow.value = getPerfSnapshot()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }, "Sunshine-Telemetry").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopTelemetryLoop() {
        ++telemetryEpoch
        try {
            telemetryThread?.interrupt()
        } catch (_: Throwable) {}
        telemetryThread = null
    }

    private fun detectConnectedPeerIps(): List<String> {
        val result = linkedSetOf<String>()
        val sunshinePortsHex = setOf("BB70", "BB75", "BB8A", "BB7E") // 47984, 47989, 48010, 47998
        listOf("/proc/net/tcp6", "/proc/net/tcp").forEach { path ->
            try {
                java.io.File(path).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val local = parts[1].uppercase()
                        val remote = parts[2].uppercase()
                        val localPort = local.substringAfter(":", "")
                        if (localPort in sunshinePortsHex) {
                            val remHex = remote.substringBefore(":", "")
                            val ip = decodeProcHexIp(remHex)
                            if (ip != null && ip != "127.0.0.1" && ip != "0.0.0.0" && !ip.startsWith("0:")) {
                                result.add(ip)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return result.toList()
    }

    private fun decodeProcHexIp(hex: String): String? {
        return try {
            if (hex.length == 8) {
                val b0 = hex.substring(6, 8).toInt(16)
                val b1 = hex.substring(4, 6).toInt(16)
                val b2 = hex.substring(2, 4).toInt(16)
                val b3 = hex.substring(0, 2).toInt(16)
                if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) null else "$b0.$b1.$b2.$b3"
            } else if (hex.length == 32 && hex.startsWith("0000000000000000FFFF0000")) {
                decodeProcHexIp(hex.substring(24, 32))
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun measurePingRttMs(ip: String, timeRegex: Regex): Double? {
        var proc: java.lang.Process? = null
        return try {
            proc = ProcessBuilder("ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
            if (!proc.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
            timeRegex.find(proc.inputStream.bufferedReader().readText())?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            proc?.destroy()
        }
    }

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
        engineClient?.setCaptureAuthorized(projection != null)
        SunshineLogManager.log("MEDIA_PROJ", if (projection != null) "MediaProjection set" else "MediaProjection cleared")
        if (projection != null && activeSessions.isNotEmpty()) captureHandler.post {
            activeSessions.toList().forEach { attachCurrentStreamSurface(it.videoThreadId, it.startTime) }
            activeSessions.firstOrNull { it.audioEnabled }?.let { startAudioCapture() }
        }
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun getCaptureDimensions(width: Int, height: Int): IntArray = displayCaptureSize(width, height)

    /**
     * 会话进行中切换「桌面类型」：直接下发到当前显示控制器，由控制器热切换桌面。
     *
     * 旧实现只在建屏前下发 setHomeType，切换后必须断开 Moonlight 重连才生效
     * （用户表现：「不能热切换桌面，必须关掉重连」）。
     */
    fun setDesktopHomeType(homeType: Int) {
        try {
            virtualScreenClient?.setHomeType(homeType)
            SunshineLogManager.log("DESKTOP_SWITCH", "热切换桌面类型 homeType=$homeType")
        } catch (t: Throwable) {
            SunshineLogManager.log("DESKTOP_SWITCH", "下发桌面类型失败: ${t.message}")
        }
    }

    private fun displayClient(): VirtualScreenClient {
        return virtualScreenClient ?: VirtualScreenClient(checkNotNull(context)) {
            captureHandler.post {
                if (_virtualScreenState.value.active) fullscreenScope.launch(Dispatchers.Main) { exitVirtualScreen() }
            }
        }.also { virtualScreenClient = it }
    }

    /** 只有 Root 与 Shizuku 都不可用时，才启用主进程本地虚拟屏链路。 */
    private fun noPrivilege(): Boolean = PrivilegeRoute.mode() == PrivilegeRoute.Mode.NONE

    /** 零权限链路的桌面密度：配置显式值优先，否则按宽度等比。 */
    private fun zeroPrivilegeDensity(ctx: Context, widthPx: Int): Int {
        val conf = try {
            java.io.File(ctx.filesDir, "sunshine.conf").readLines()
                .firstOrNull { it.trim().startsWith("virtual_screen_density") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        } catch (_: Exception) { null }
        return conf?.coerceIn(80, 640) ?: when {
            widthPx >= 3840 -> 360
            widthPx >= 2560 -> 240
            widthPx >= 1920 -> 213
            else -> 160
        }
    }

    private fun attachAllSessionSurfaces() {
        for (s in activeSessions) {
            s.surface?.takeIf { it.isValid }?.let {
                frameDispatcher.addSurface(it, s.width, s.height, s.fps, s.videoThreadId)
            }
        }
    }

    /**
     * 零权限画面链路（主线程同步）：
     * FrameDispatcher 采集 Surface → DisplayManager.createVirtualDisplay(…, 0)
     * → DeXPresentation.show() 到该 Display → GPU 每帧直进编码器。
     * 不碰 MediaProjection、不碰 VirtualScreenClient、不碰任何 shell/Shizuku 通道。
     */
    @Synchronized
    private fun ensureZeroPrivilegeScreen(w: Int, h: Int, dpi: Int): Boolean {
        val ctx = context ?: return false
        val local = LocalMoonlightDisplay.get()
        if (local.isActive() && frameDispatcher.sourceWidth == w && frameDispatcher.sourceHeight == h) {
            attachAllSessionSurfaces()
            return true
        }
        val previousState = _virtualScreenState.value
        // 旧虚拟屏仍持有即将被 resize 释放的采集 Surface，必须先摘掉，否则会黑屏。
        // 重建期间置 busy：DisplayListener 的 onDisplayRemoved 是异步的，若把旧屏
        // 移除误判成「当前屏断开」，会把刚建好的新屏又退出掉。
        _virtualScreenState.value = previousState.copy(busy = true)
        var created = false
        try {
            local.release()
            val surface = try {
                when {
                    !frameDispatcher.isRunning -> frameDispatcher.start(w, h)
                    frameDispatcher.sourceWidth != w || frameDispatcher.sourceHeight != h ->
                        frameDispatcher.resizeCaptureSync(w, h) ?: return false
                    else -> frameDispatcher.captureSurface() ?: return false
                }
            } catch (error: Exception) {
                SunshineLogManager.log("VD_ERR", "零权限采集画布启动失败: ${error.message}")
                return false
            }
            val id = local.ensure(ctx, surface, w, h, dpi)
            if (id <= 0) return false
            frameDispatcher.setVirtualSource(true, 60f)
            frameDispatcher.sourceRotation = 0
            // 必须先置 active：sourceDisplayId 是 _virtualScreenState 的派生 getter，
            // configureCanvas 会把它下发给 :engine 的 InputBroker 作为坐标映射基准。
            // 若早于这行调用，InputBroker 仍以为源屏是手机主屏（竖屏），会把客户端
            // 横屏坐标按主屏 aspect-fit 缩放，导致点击全部落到画面之外。
            _virtualScreenState.value = VirtualScreenState(active = true, displayId = id,
                width = w, height = h, mode = "desktop", launcher = "沧海桌面（零权限本地虚拟屏）",
                actualRefreshRate = 60f, desktopControls = false)
            engineClient?.configureCanvas(w, h, false, false)
            attachAllSessionSurfaces()
            com.aosen.shizukudex.ScreenRegistry.get().onStreamDisplayReady(id, w, h, "local")
            SunshineLogManager.log("VIRTUAL_SCREEN", "Zero-permission local VirtualDisplay id=$id ${w}x${h}@${dpi}dpi")
            created = true
            return true
        } finally {
            if (!created) _virtualScreenState.value = previousState
        }
    }

    suspend fun createVirtualScreen(): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        if (_virtualScreenState.value.busy || fullscreenBusy.value) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        if (_virtualScreenState.value.active) return@withContext FullscreenDisplay.Result(true, "虚拟屏已创建")
        val session = activeSessions.firstOrNull() ?: return@withContext FullscreenDisplay.Result(false, "先连接 Moonlight，再创建虚拟屏")
        val ctx = context ?: return@withContext FullscreenDisplay.Result(false, "服务未启动")
        // 沧海集成：虚拟屏串流不需要 MediaProjection——采集 surface 由当前通道的
        // display controller 直接绑定虚拟屏输出：Shizuku 授权时是 shell 进程里的
        // VirtualDisplayUserService，纯 Root 时是主进程本地的 RootDisplayController
        // （经常驻 root 桥以 UID 0 建屏）。这里只需确保 GL 采集画布已启动。
        if (!frameDispatcher.isRunning) {
            val size = displayCaptureSize(session.width, session.height)
            try {
                frameDispatcher.start(size[0], size[1])
            } catch (error: Exception) {
                return@withContext FullscreenDisplay.Result(false, "采集画布启动失败: ${error.message}")
            }
        }
        val props = java.io.File(ctx.filesDir, "sunshine.conf").readLines().mapNotNull { line ->
            val parts = line.trim().takeUnless { it.startsWith("#") }?.split('=', limit = 2)
            if (parts?.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
        val mode = if (props["virtual_screen_mode"] == "desktop") "desktop" else "screen_off"
        // 沧海集成：三星 DeX 桌面按「逻辑宽度」(px/densityDpi×160) 布局，要使
        // Moonlight 虚拟屏与有线桌面屏显示一致的内容，两屏逻辑宽度必须相等，
        // 即：Sunshine 密度 = 有线屏密度 × (Sunshine 宽 / 有线屏宽)。
        // 有线屏参数直接从系统读取（Aosen_VD_Desk_* 虚拟屏，密度跟随应用的
        // DPI 设置，如 4096×2160@213dpi）；未连接有线屏时按 213dpi 参考等比。
        // sunshine.conf 的 virtual_screen_density 显式配置仍然优先。
        val densityConf = props["virtual_screen_density"]?.toIntOrNull()
        fun aosenDesktopDensity(widthPx: Int): Int {
            if (densityConf != null) return densityConf.coerceIn(80, 640)
            return when {
                widthPx >= 3840 -> 360
                widthPx >= 2560 -> 240
                widthPx >= 1920 -> 213
                else -> 160
            }
        }
        val native = engineClient?.videoSessions()?.firstOrNull { it.threadId == session.videoThreadId }
        val requested = FullscreenDisplay.Size(native?.requestedWidth?.takeIf { it > 0 } ?: session.width,
            native?.requestedHeight?.takeIf { it > 0 } ?: session.height)
        // 沧海集成：desktop 模式优先使用 Moonlight 客户端请求的分辨率（横屏 1920x1080），
        // 避免虚拟屏跟随手机主屏比例变成竖长条；仅 screen_off 模式保持主屏尺寸。
        val size = if (_streamDisplayMode.value == StreamDisplayMode.DEFAULT && mode != "desktop")
            primaryDefaultSize() else requested
        val width = size.width
        val height = size.height
        val refreshRate = minOf(native?.requestedFps?.takeIf { it > 0 } ?: session.fps, streamingSettings.value.maxFps).coerceIn(15, 240)
        val systemDesktop = props["virtual_screen_desktop"] == "system"
        val samsungDex = systemDesktop && Build.MANUFACTURER.equals("samsung", true)
        val highRefresh = props["experimental_desktop_high_refresh"] != "false"
        _virtualScreenState.value = VirtualScreenState(busy = true, width = width, height = height, mode = mode)
        try {
            // 无 Root / 无 Shizuku：主进程本地虚拟屏 + 沧海桌面，零权限、零录屏弹窗。
            // 有任一特权时这里绝不进入，下面原有 Shizuku / Root 建屏链路完全不变。
            if (noPrivilege()) {
                check(ensureZeroPrivilegeScreen(width, height, aosenDesktopDensity(width))) {
                    "零权限本地虚拟屏创建失败"
                }
                return@withContext FullscreenDisplay.Result(true,
                    "已创建 $width × $height 零权限本地虚拟屏 · 沧海桌面")
            }
            val client = displayClient()
            client.connect()
            // 沧海集成：虚拟屏桌面跟随「桌面类型」选择（0=官方桌面，1=沧海桌面），
            // 每次建屏前下发一次，设置变更后重建即生效。
            val homeTypePref = try {
                ctx.getSharedPreferences("aosen_settings", Context.MODE_PRIVATE).getInt("pref_home_type", 0)
            } catch (_: Exception) { 0 }
            client.setHomeType(homeTypePref)
            if (mode == "screen_off") {
                check(withContext(Dispatchers.IO) { client.blankMain() }) { "无法关闭主屏显示面板" }
                check(activeSessions.any { it.videoThreadId == session.videoThreadId }) { "连接已结束" }
                _virtualScreenState.value = VirtualScreenState(active = true, displayId = 0,
                    width = frameDispatcher.sourceWidth, height = frameDispatcher.sourceHeight, mode = mode, launcher = "原主屏画面",
                    mainPanelOff = true)
                SunshineLogManager.log("SCREEN_OFF_STREAM", "Physical panel blanked; original display and input remain active")
                return@withContext FullscreenDisplay.Result(true, "已开始主屏息屏串流，继续显示原主屏画面")
            }
            if (samsungDex) {
                val pending = withContext(Dispatchers.IO) { client.prepareLocalDex() }
                if (pending) {
                    preparingLocalDex = true
                    val route = org.json.JSONObject(client.snapshot()).optString("dex_route_detail")
                    _virtualScreenState.value = _virtualScreenState.value.copy(detail = route.ifBlank { "请在三星 DeX 列表选择 Sunshine Local DeX，并确认立即开始" })
                    var ready = false
                    // This includes human interaction with Samsung's device list,
                    // consent and first-use pages. A one-minute deadline can tear
                    // down the receiver while the user is still confirming it.
                    val confirmationDeadline = android.os.SystemClock.uptimeMillis() + 180_000
                    while (android.os.SystemClock.uptimeMillis() < confirmationDeadline) {
                        check(activeSessions.any { it.videoThreadId == session.videoThreadId }) { "连接已结束" }
                        val status = withContext(Dispatchers.IO) { org.json.JSONObject(client.snapshot()) }
                        val stage = status.optString("local_dex_stage")
                        val hint = when {
                            stage.contains("main_channel") || stage.contains("connection_allowed") -> "请确认三星系统弹出的立即开始；正在建立本机 DeX"
                            stage.startsWith("wfd_") -> "三星 DeX 已连接，正在等待系统桌面显示"
                            else -> route.ifBlank { "请在三星 DeX 列表选择 Sunshine Local DeX，再确认立即开始" }
                        }
                        if (_virtualScreenState.value.detail != hint) _virtualScreenState.value = _virtualScreenState.value.copy(detail = hint)
                        check(status.optString("local_dex_error").isEmpty()) { "本机 DeX：${status.optString("local_dex_error")}" }
                        if (status.optBoolean("local_dex_ready")) { ready = true; break }
                        kotlinx.coroutines.delay(250)
                    }
                    check(ready) { "三星 DeX 等待超时：${_virtualScreenState.value.detail}" }
                    preparingLocalDex = false
                }
            }
            // Resize the real external DeX workspace before allocating the capture
            // surface. Primary/tablet DeX keeps its existing main-screen dimensions.
            val ready = org.json.JSONObject(withContext(Dispatchers.IO) { client.configureDesktop(width, height, samsungDex,
                _streamDisplayMode.value == StreamDisplayMode.FULLSCREEN) })
            val captureWidth = if (samsungDex) ready.optInt("native_dex_width").takeIf { it in 1..8192 } ?: width else width
            val captureHeight = if (samsungDex) ready.optInt("native_dex_height").takeIf { it in 1..8192 } ?: height else height
            virtualDisplay?.release(); virtualDisplay = null
            val surface = frameDispatcher.resizeCaptureSync(captureWidth, captureHeight) ?: error("无法切换虚拟屏采集画布")
            val id = withContext(Dispatchers.IO) { client.create(captureWidth, captureHeight, aosenDesktopDensity(captureWidth), surface, mode, refreshRate, systemDesktop, highRefresh) }
            val created = org.json.JSONObject(client.snapshot())
            check(id > 0 || (id == 0 && created.optBoolean("system_dex_mirror") && created.optBoolean("dex_active"))) { "系统没有返回有效的桌面显示 ID" }
            check(activeSessions.any { it.videoThreadId == session.videoThreadId }) { "创建期间连接已结束" }
            val snapshot = org.json.JSONObject(client.snapshot())
            snapshot.optString("local_dex_stage").takeIf { it.startsWith("wfd_status:") }?.let {
                SunshineLogManager.log("DEX_TRANSPORT", it.removePrefix("wfd_status:"))
            }
            val description = snapshot.optString("launcher", "Sunshine 桌面")
            _virtualScreenState.value = VirtualScreenState(active = true, displayId = id, width = captureWidth, height = captureHeight,
                mode = mode, launcher = description, requestedRefreshRate = snapshot.optInt("requested_refresh_rate"),
                actualRefreshRate = snapshot.optDouble("actual_refresh_rate", 0.0).toFloat(),
                refreshRateRequestSupported = snapshot.optBoolean("refresh_rate_request_supported"),
                dexRequested = samsungDex, dexActive = snapshot.optBoolean("dex_active"), detail = snapshot.optString("detail"),
                systemDexMirror = snapshot.optBoolean("system_dex_mirror"), desktopControls = false,
                retainedDisplayId = snapshot.optInt("retained_display_id", -1))
            com.aosen.shizukudex.ScreenRegistry.get().onStreamDisplayReady(id, captureWidth, captureHeight, "privileged")
            // 串流屏就绪 = 这台客户端对外的「虚拟外设」插上了：Root/Shizuku 链路同样要登记，
            // 否则手机首页外设里没有 Moonlight 卡片，物理外设也就无处可拖。
            try { com.aosen.shizukudex.PeripheralBrain.getInstance().onMoonlightRemoteAttached() } catch (_: Throwable) {}
            frameDispatcher.setVirtualSource(true, snapshot.optDouble("actual_refresh_rate", 60.0).toFloat())
            frameDispatcher.sourceRotation = 0
            engineClient?.configureCanvas(captureWidth, captureHeight, false, false)
            SunshineLogManager.log("VIRTUAL_SCREEN", "Created display=$id, ${captureWidth}x$captureHeight, mode=$mode, launcher=$description")
            // 沧海集成：AUTO_SCREEN 兜底路径下，会话 surface 在 streamStarted 时因虚拟屏
            // 尚未建立而未挂接（走 VD_WARN）。虚拟屏就绪后这里补挂全部活跃会话 surface。
            for (s in activeSessions) {
                s.surface?.takeIf { it.isValid }?.let {
                    frameDispatcher.addSurface(it, s.width, s.height, s.fps, s.videoThreadId)
                    SunshineLogManager.log("VIRTUAL_SCREEN", "Late-attached client surface (tid=${s.videoThreadId})")
                }
            }
            FullscreenDisplay.Result(true, if (snapshot.optBoolean("system_dex_mirror"))
                "已开始串流 $captureWidth × $captureHeight · $description" else "已创建 $captureWidth × $captureHeight 虚拟屏 · $description")
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { try { virtualScreenClient?.close() } catch (_: Exception) {} }
            val message = DisplayFailureMessage.from(error)
            _virtualScreenState.value = VirtualScreenState(busy = true, error = message)
            if (mode == "desktop") restorePhysicalCapture()
            SunshineLogManager.log("VIRTUAL_SCREEN_ERR", message)
            FullscreenDisplay.Result(false, "虚拟屏未创建：$message")
        } finally {
            preparingLocalDex = false
            _virtualScreenState.value = _virtualScreenState.value.copy(busy = false)
        }
    }

    private fun restorePhysicalCapture() {
        frameDispatcher.setVirtualSource(false)
        // 零权限链路没有 MediaProjection token，不能回落主屏镜像。
        if (noPrivilege()) return
        val mp = mediaProjection ?: return
        val dimensions = displayCaptureSize(lastWidth, lastHeight)
        val surface = frameDispatcher.resizeCaptureSync(dimensions[0], dimensions[1]) ?: return
        virtualDisplay?.release()
        virtualDisplay = mp.createVirtualDisplay("SunshineVirtualDisplay", dimensions[0], dimensions[1],
            context?.resources?.displayMetrics?.densityDpi ?: 300, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        frameDispatcher.sourceRotation = captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        engineClient?.configureCanvas(dimensions[0], dimensions[1], autoRotateEnabled, rotationCompatEnabled)
    }

    suspend fun exitVirtualScreen(): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        if (_virtualScreenState.value.busy) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        if (!_virtualScreenState.value.active) return@withContext FullscreenDisplay.Result(true, "虚拟屏已退出")
        val previous = _virtualScreenState.value
        // 零权限链路只需要释放本地 VirtualDisplay + DeXPresentation；采集器与编码器
        // 保留给下一个客户端，绝不回落 MediaProjection 主屏镜像。
        if (noPrivilege()) {
            _virtualScreenState.value = previous.copy(busy = true)
            LocalMoonlightDisplay.get().release()
            frameDispatcher.setVirtualSource(false)
            _virtualScreenState.value = VirtualScreenState()
            SunshineLogManager.log("VIRTUAL_SCREEN", "Released zero-permission local VirtualDisplay")
            return@withContext FullscreenDisplay.Result(true, "已退出零权限本地虚拟屏")
        }
        _virtualScreenState.value = previous.copy(busy = true)
        try {
            withContext(Dispatchers.IO) { virtualScreenClient?.close() }
            _virtualScreenState.value = VirtualScreenState(busy = true)
            if (previous.mode == "desktop") restorePhysicalCapture()
            SunshineLogManager.log("VIRTUAL_SCREEN", "Released display mode; physical capture restored")
            FullscreenDisplay.Result(true, "已退出虚拟屏并恢复主屏")
        } catch (e: Exception) {
            _virtualScreenState.value = if (previous.mode == "screen_off") previous.copy(busy = true, error = e.message)
                else VirtualScreenState(busy = true, error = e.message)
            if (previous.mode == "desktop") try { restorePhysicalCapture() } catch (_: Exception) {}
            FullscreenDisplay.Result(false, "虚拟屏退出时出现问题：${e.message}")
        } finally {
            _virtualScreenState.value = _virtualScreenState.value.copy(busy = false)
            if (activeSessions.isNotEmpty()) displayModeChanges.trySend(Unit)
        }
    }

    suspend fun launchVirtualApp(component: String): Boolean = withContext(Dispatchers.IO) {
        if (!_virtualScreenState.value.active) false else virtualScreenClient?.launch(component) == true
    }

    suspend fun virtualDesktopTasks(): String = withContext(Dispatchers.IO) {
        if (!_virtualScreenState.value.active) "[]" else virtualScreenClient?.tasks() ?: "[]"
    }

    suspend fun virtualDesktopAction(action: String, taskId: Int = -1): Boolean = withContext(Dispatchers.IO) {
        if (!_virtualScreenState.value.active) false else virtualScreenClient?.action(action, taskId) == true
    }

    suspend fun leaveSunshineDesktop(): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        val previous = _virtualScreenState.value
        if (!previous.active || previous.mode != "desktop" || previous.systemDexMirror)
            return@withContext FullscreenDisplay.Result(false, "当前没有 Sunshine 虚拟桌面")
        if (previous.busy) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        // 零权限本地虚拟屏没有 display controller，退出桌面就是释放这块本地虚拟屏。
        if (noPrivilege() && LocalMoonlightDisplay.get().isActive()) {
            return@withContext exitVirtualScreen()
        }
        if (!_fullscreenBusy.compareAndSet(false, true))
            return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        _virtualScreenState.value = previous.copy(busy = true)
        var physicalBefore: FullscreenDisplay.Snapshot? = null
        val restorePrefs = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE)
        val alreadySaved = restorePrefs?.contains("original") == true
        try {
            check(activeSessions.isNotEmpty()) { "连接已结束" }
            // Reconnect capture to the phone before blanking its physical panel.
            _virtualScreenState.value = previous.copy(busy = true, mode = "screen_off", displayId = Display.DEFAULT_DISPLAY,
                launcher = "手机主屏（保留虚拟屏）")
            restorePhysicalCapture()
            val fit = withContext(Dispatchers.IO) {
                val backend = fullscreenBackend(rememberOriginal = true)
                physicalBefore = backend.snapshot()
                FullscreenDisplay.apply(FullscreenDisplay.Size(previous.width, previous.height), backend) {
                    activeSessions.isNotEmpty()
                }
            }
            check(fit.applied) { fit.message }
            check(withContext(Dispatchers.IO) { displayClient().action("leave") }) { "未能返回手机主屏" }
            _virtualScreenState.value = previous.copy(busy = true, mode = "screen_off", displayId = Display.DEFAULT_DISPLAY,
                launcher = "手机主屏（保留虚拟屏）", mainPanelOff = true)
            SunshineLogManager.log("VIRTUAL_HOME_EXIT", "Secondary HOME released; primary captured fullscreen with retained display and panel off")
            FullscreenDisplay.Result(true, "已返回手机主屏，保留虚拟屏和全屏息屏串流")
        } catch (error: Exception) {
            // A capture switch failure must never advertise an old secondary
            // display while the dispatcher is already mirroring the primary.
            withContext(Dispatchers.IO) { runCatching { virtualScreenClient?.close() } }
            _virtualScreenState.value = VirtualScreenState(busy = true, error = error.message)
            val restored = withContext(Dispatchers.IO) {
                physicalBefore?.let { FullscreenDisplay.restore(it, fullscreenBackend()) } ?: true
            }
            if (restored && !alreadySaved) restorePrefs?.edit()?.remove("original")?.commit()
            runCatching { restorePhysicalCapture() }
            FullscreenDisplay.Result(false, if (restored) error.message ?: "退出桌面失败"
                else "退出桌面失败，原主屏显示设置未恢复，请检查 Shizuku")
        } finally {
            _virtualScreenState.value = _virtualScreenState.value.copy(busy = false)
            _fullscreenBusy.value = false
            if (activeSessions.isEmpty()) {
                if (_virtualScreenState.value.active) exitVirtualScreen()
                restoreFullscreenIfIdle()
            }
        }
    }

    suspend fun setVirtualScreenPanelOff(off: Boolean): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        val previous = _virtualScreenState.value
        if (!previous.active || previous.mode != "desktop") return@withContext FullscreenDisplay.Result(false, "请先创建独立第二屏")
        if (previous.busy) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        if (noPrivilege()) return@withContext FullscreenDisplay.Result(false, "零权限本地虚拟屏模式下不支持关闭手机主屏面板")
        _virtualScreenState.value = previous.copy(busy = true)
        try {
            check(withContext(Dispatchers.IO) { displayClient().setMainPanelOff(off) }) { "主屏面板操作未完成" }
            check(activeSessions.isNotEmpty()) { "连接已结束，正在恢复主屏" }
            _virtualScreenState.value = previous.copy(busy = true, mainPanelOff = off, error = null)
            SunshineLogManager.log("VIRTUAL_PANEL", "Physical main panel off=$off; virtual display ${previous.displayId} preserved")
            FullscreenDisplay.Result(true, if (off) "主屏面板已关闭，第二屏继续串流" else "主屏面板已恢复")
        } catch (error: Exception) {
            _virtualScreenState.value = previous.copy(busy = true, error = (error.cause ?: error).message)
            FullscreenDisplay.Result(false, "主屏面板操作失败：${(error.cause ?: error).message}")
        } finally {
            _virtualScreenState.value = _virtualScreenState.value.copy(busy = false)
            if (activeSessions.isEmpty() && _virtualScreenState.value.active) exitVirtualScreen()
        }
    }

    fun submitPin(pin: String): Boolean = pinPairing.submitPin(pin)

    fun onPinRequested() = pinPairing.onPinRequested()

    fun dismissPinRequest() = pinPairing.dismissPinRequest()

    @Synchronized
    fun onStreamStarted(width: Int, height: Int, fps: Int, packetDuration: Int, surface: Surface,
                        shouldSendAudio: Boolean, callerThreadId: Long, appId: Int = 1, clientId: String = "", clientAddress: String = "") {
        val sessionIndex = activeSessions.size + 1
        reloadConfig()
        val peerIps = detectConnectedPeerIps()
        if (peerIps.isNotEmpty()) {
            lastDiscoveredClient = peerIps.last()
        }
        val cIp = clientAddress.takeIf { it.isNotBlank() } ?: peerIps.lastOrNull() ?: lastDiscoveredClient ?: "127.0.0.1"
        val pairedName = try {
            context?.let { ctx ->
                val root = com.google.gson.JsonParser.parseString(java.io.File(ctx.filesDir, "sunshine_state.json").readText()).asJsonObject.getAsJsonObject("root")
                root?.getAsJsonArray("named_devices")?.firstOrNull { it.asJsonObject.get("uuid")?.asString?.equals(clientId, true) == true }
                    ?.asJsonObject?.get("name")?.asString
            }
        } catch (_: Exception) { null }
        val cName = pairedName ?: if (cIp != "127.0.0.1") "Moonlight ($cIp)" else "Moonlight Client #$sessionIndex"
        SunshineLogManager.log("STREAM", "Stream started ($cName, tid=$callerThreadId): ${width}x$height @ ${fps}fps, packetDuration=${packetDuration}ms, audio=$shouldSendAudio")

        lastWidth = width
        lastHeight = height
        if (activeSessions.isEmpty()) {
            lastBitrate = 0
            lastFps = 0.0
            lastEncodeTimeMs = 0.0
            lastNetworkLatencyMs = null
            lastNetworkSampleMs = 0L
            lastNetworkProbeIp = null
        }
        if (streamStartTime == 0L) {
            streamStartTime = System.currentTimeMillis()
        }

        com.alkaidlab.sunshine.engine.InputBroker.setActiveClientDimensions(width, height)

        val selectedApp = try { context?.let { AppCatalog.read(java.io.File(it.filesDir, "apps.json")).apps.firstOrNull { app -> app.id?.toIntOrNull() == appId } } } catch (_: Exception) { null }
        val session = RuntimeSession(
            clientName = cName,
            clientIp = cIp,
            width = width,
            height = height,
            fps = fps,
            bitrate = 0, // JNI does not report this session's negotiated or measured bitrate.
            codec = lastCodec,
            startTime = System.currentTimeMillis(),
            surface = surface,
            videoThreadId = callerThreadId,
            audioEnabled = shouldSendAudio,
            appId = appId, clientUuid = clientId.takeIf { it.isNotBlank() }, appName = selectedApp?.name
        )
        if (selectedApp?.singleApp == true && !selectedApp.cmd.isNullOrBlank()) ensureAppGuard()?.attach(callerThreadId, selectedApp.cmd)
        activeSessions.add(session)
        com.aosen.shizukudex.ScreenRegistry.get().onStreamSessionStarted(session.clientName, width, height)
        _activeSessionsFlow.value = activeSessions.toList()
        _isStreaming.value = true
        _perfFlow.value = getPerfSnapshot()
        startTelemetryLoop()
        // 沧海集成：串流前自动授予录音权限（重装后授权会丢失）；静音 HDMI 媒体音量，
        // 避免串流桌面的声音从外接显示器音响播放（停止后自动恢复）。
        ensureAudioPermission()
        muteHdmiMediaVolume()

        // Codec rotation may replace the initial Surface before this task runs.
        captureHandler.post {
            attachCurrentStreamSurface(callerThreadId, session.startTime)
            if (activeSessions.none { it.videoThreadId == callerThreadId && it.startTime == session.startTime }) return@post
            displayModeChanges.trySend(Unit)

            // Start Audio capture if requested
            if (shouldSendAudio) {
                startAudioCapture(packetDuration)
            } else {
                SunshineLogManager.log("AUDIO", "Client requested no audio")
            }
        }
    }

    @Synchronized
    private fun attachCurrentStreamSurface(threadId: Long, startTime: Long) {
        val session = activeSessions.firstOrNull { it.videoThreadId == threadId && it.startTime == startTime } ?: return
        val surface = session.surface?.takeIf { it.isValid } ?: return
        // Moonlight 客户端一连上来就建屏：零权限链路完全不依赖 MediaProjection，
        // 不会触发任何录屏授权弹窗；特权链路继续走下面的原有分支。
        if (noPrivilege()) {
            val ctx = context
            if (ctx != null) {
                val native = engineClient?.videoSessions()?.firstOrNull { it.threadId == threadId }
                val w = native?.requestedWidth?.takeIf { it > 0 } ?: session.width
                val h = native?.requestedHeight?.takeIf { it > 0 } ?: session.height
                frameDispatcher.addSurface(surface, session.width, session.height, session.fps, threadId)
                if (!ensureZeroPrivilegeScreen(w, h, zeroPrivilegeDensity(ctx, w))) {
                    SunshineLogManager.log("VD_ERR", "零权限本地虚拟屏未就绪；等待自动兜底重试")
                }
            }
            return
        }
        val mp = mediaProjection
        if (_virtualScreenState.value.active || _virtualScreenState.value.busy) {
            frameDispatcher.addSurface(surface, session.width, session.height, session.fps, threadId)
        } else if (mp != null) {
            try {
                if (!frameDispatcher.isRunning || virtualDisplay == null) {
                    val size = displayCaptureSize(session.width, session.height)
                    val inputSurface = frameDispatcher.start(size[0], size[1])
                    val density = context?.resources?.displayMetrics?.densityDpi ?: 300
                    virtualDisplay?.release()
                    virtualDisplay = mp.createVirtualDisplay("SunshineVirtualDisplay", size[0], size[1], density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null)
                    SunshineLogManager.log("VD", "VirtualDisplay captures ${size[0]}x${size[1]} display pixels; client=${session.width}x${session.height} @ ${density}dpi")
                }
                syncCaptureSize()
                frameDispatcher.addSurface(surface, session.width, session.height, session.fps, threadId)
                SunshineLogManager.log("VD", "Client (${session.clientName}) current surface registered into frame dispatcher")
            } catch (error: Exception) {
                SunshineLogManager.log("VD_ERR", "Error initializing VirtualDisplay/Dispatcher: ${error.message}")
            }
        } else SunshineLogManager.log("VD_WARN", "MediaProjection is null, cannot create VirtualDisplay yet")
    }

    @Synchronized
    fun pauseStreamSurface(callerThreadId: Long): Boolean {
        val session = activeSessions.find { it.videoThreadId == callerThreadId } ?: return false
        session.surface?.let { frameDispatcher.removeSurfaceSync(it) }
        val index = activeSessions.indexOf(session)
        activeSessions[index] = session.copy(surface = null)
        _activeSessionsFlow.value = activeSessions.toList()
        return true
    }

    @Synchronized
    fun resizeStreamSurface(callerThreadId: Long, width: Int, height: Int, surface: Surface): Boolean {
        val session = activeSessions.find { it.videoThreadId == callerThreadId } ?: return false
        if (width <= 0 || height <= 0 || !surface.isValid) return false
        session.surface?.let { frameDispatcher.removeSurfaceSync(it) }
        activeSessions[activeSessions.indexOf(session)] = session.copy(width = width, height = height, surface = surface)
        if (frameDispatcher.isRunning) frameDispatcher.addSurface(surface, width, height, session.fps, callerThreadId)
        else captureHandler.post { attachCurrentStreamSurface(callerThreadId, session.startTime) }
        lastWidth = width
        lastHeight = height
        _activeSessionsFlow.value = activeSessions.toList()
        SunshineLogManager.log("CANVAS", "Encoder canvas resized to ${width}x$height")
        return true
    }

    @Synchronized
    fun onStreamStopped(callerThreadId: Long) {
        appSessionGuard?.detach(callerThreadId)
        val deadSession = activeSessions.find { it.videoThreadId == callerThreadId } ?: return
        activeSessions.remove(deadSession)
        deadSession.surface?.let { surface ->
            // The synchronous Binder callback keeps native AMediaCodec_stop behind EGL teardown.
            frameDispatcher.removeSurfaceSync(surface)
            surface.release()
        }
        SunshineLogManager.log("STREAM", "Disconnected tid=$callerThreadId; remaining=${activeSessions.size}")
        val remaining = activeSessions.lastOrNull()
        if (remaining != null) {
            com.alkaidlab.sunshine.engine.InputBroker.setActiveClientDimensions(remaining.width, remaining.height)
        } else {
            // 串流屏注销统一交给注册表：它会发「屏没了」，外设回流由大脑订阅完成，
            // 这里不再自己调 onDisplayDisconnected，避免两处各判一次。
            com.aosen.shizukudex.ScreenRegistry.get().onStreamGone()
            try {
                com.aosen.shizukudex.PeripheralBrain.getInstance().onMoonlightRemoteDetached()
            } catch (_: Throwable) {}
            com.alkaidlab.sunshine.engine.InputBroker.resetCursorMode()
            _isStreaming.value = false
            // 只收串流屏那支光标；真实外屏上的鼠标由大脑层的真实绑定维持，不能被一起抹掉。
            RemoteCursorOverlay.hideOnly(_virtualScreenState.value.displayId)
            restoreHdmiMediaVolume()
            if (_virtualScreenState.value.active) fullscreenScope.launch(Dispatchers.Main) { exitVirtualScreen() }
            stopTelemetryLoop()
            streamStartTime = 0L
            restoreFullscreenIfIdle()
            // Preserve projection/display/recorder, but stop draining audio while idle.
        }
        if (activeSessions.none { it.audioEnabled }) pauseAudioCapture()
        _activeSessionsFlow.value = activeSessions.toList()
        _perfFlow.value = getPerfSnapshot()
        if (activeSessions.isNotEmpty()) displayModeChanges.trySend(Unit)
    }

    @Synchronized
    fun onEngineDisconnected() {
        appSessionGuard?.clear()
        activeSessions.toList().forEach { onStreamStopped(it.videoThreadId) }
        pinPairing.clear()
        audioPacketFloats = 0
        audioBuffer = PcmAudioBuffer(audioBufferMs * 96)
    }

    fun onProjectionStopped(projection: MediaProjection) {
        Handler(Looper.getMainLooper()).post {
            if (mediaProjection !== projection) return@post
            // The framework already stopped this token. Only release resources
            // owned by the expired session instead of stopping the token again.
            releaseCapture(stopProjection = false)
            SunshineLogManager.log("MEDIA_PROJ", "Capture revoked; waiting for fresh user consent")
            captureRecovery?.onCaptureLost()
        }
    }

    fun triggerAutoRecovery() {
        Handler(Looper.getMainLooper()).post { engineClient?.scheduleRecovery() }
    }

    @Synchronized
    fun onClientDiscovered(client: String) {
        SunshineLogManager.log("DISCOVERY", "Discovered client: $client")
        lastDiscoveredClient = client
        if (activeSessions.isNotEmpty()) {
            val updated = activeSessions.map { s ->
                if (s.clientName.startsWith("Moonlight Client") || s.clientIp == "127.0.0.1") {
                    s.copy(clientName = if (s.clientUuid.isNullOrBlank()) client else s.clientName, clientIp = client)
                } else s
            }
            activeSessions.clear()
            activeSessions.addAll(updated)
            _activeSessionsFlow.value = activeSessions.toList()
        }
    }

    fun setServerUuid(uuid: String) {
        this.clientUuid = uuid
        SunshineLogManager.log("SERVER", "Server UUID: $uuid")
    }

    fun isClientActive(uuid: String): Boolean {
        return activeSessions.any { it.clientUuid?.equals(uuid, true) == true }
    }

    fun getActiveSessions(): List<RuntimeSession> {
        val native = engineClient?.videoSessions().orEmpty().associateBy { it.threadId }
        return activeSessions.map { host ->
            native[host.videoThreadId]?.let { host.copy(sessionId = it.sessionId,
                bitrate = it.appliedBitrateKbps, codec = it.codec) } ?: host
        }
    }

    fun controlVideo(sessionId: Long, operation: Int, value: Int = 0): Boolean =
        engineClient?.controlVideo(sessionId, operation, value) == true

    private fun rememberFullscreenDisplay(before: FullscreenDisplay.Snapshot) {
        val prefs = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE) ?: error("No host context")
        if (prefs.contains("original")) return
        val data = org.json.JSONObject().put("width", before.override?.width ?: 0)
            .put("height", before.override?.height ?: 0).put("userRotation", before.userRotation)
            .put("rotation", before.rotation)
            .put("physicalWidth", before.physical.width).put("physicalHeight", before.physical.height)
            .put("autoRotate", before.autoRotate).put("fixedRotation", before.fixedRotation)
        check(prefs.edit().putString("original", data.toString()).commit())
    }

    private fun fullscreenBackend(rememberOriginal: Boolean = false): FullscreenDisplay.Backend {
        // Some Android versions print valid wm help with exit code -1 (255); mutations still require 0.
        val help = PrivilegedShell.run(arrayOf("wm", "help"), acceptedExitCodes = setOf(0, 1, 255)).orEmpty()
        val fixedCommand = FullscreenDisplay.rotationCommand(help, fixed = true)
        val rotationCommand = FullscreenDisplay.rotationCommand(help)
        return object : FullscreenDisplay.Backend {
            override fun rotation() = captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
            override fun snapshot(): FullscreenDisplay.Snapshot? {
                val output = PrivilegedShell.run(arrayOf("wm", "size")) ?: return null
                val windows = PrivilegedShell.run(arrayOf("dumpsys", "window", "displays")) ?: return null
                val fixed = FullscreenDisplay.parseFixedRotation(windows) ?: return null
                val resolver = context?.contentResolver ?: return null
                return FullscreenDisplay.parseOverride(output)?.copy(rotation = rotation(), fixedRotation = fixed,
                    autoRotate = android.provider.Settings.System.getInt(resolver, "accelerometer_rotation", 0) != 0,
                    userRotation = android.provider.Settings.System.getInt(resolver, "user_rotation", rotation()))
                    ?.also { if (rememberOriginal) rememberFullscreenDisplay(it) }
            }
            override fun setFixedRotation(enabled: Boolean): Boolean = fixedCommand != null &&
                PrivilegedShell.run(arrayOf("wm", fixedCommand, if (enabled) "enabled" else "disabled"), 3000) != null
            override fun setRotation(rotation: Int, autoRotate: Boolean): Boolean = rotationCommand != null &&
                PrivilegedShell.run(if (autoRotate) arrayOf("wm", rotationCommand, "free")
                    else arrayOf("wm", rotationCommand, "lock", rotation.toString()), 3000) != null
            override fun setOverride(size: FullscreenDisplay.Size?) =
                PrivilegedShell.run(arrayOf("wm", "size", size?.toString() ?: "reset"), 3000) != null
            override fun awaitDisplay(size: FullscreenDisplay.Size): Boolean {
                repeat(30) {
                    val current = primaryDisplaySize()
                    if (current[0] == size.width && current[1] == size.height) return true
                    Thread.sleep(50)
                }
                return false
            }
            override fun syncCapture(): Boolean {
                val latch = CountDownLatch(1)
                var synced = false
                captureHandler.post {
                    try { synced = !frameDispatcher.isRunning || virtualDisplay == null || syncCaptureSize() }
                    finally { latch.countDown() }
                }
                return latch.await(6, TimeUnit.SECONDS) && synced
            }
        }
    }

    private fun primaryDisplaySize(): IntArray {
        val point = Point()
        @Suppress("DEPRECATION") captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point)
        return intArrayOf(point.x, point.y)
    }

    private fun primaryDefaultSize(): FullscreenDisplay.Size {
        val raw = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE)?.getString("original", null)
        if (raw != null) try {
            val data = org.json.JSONObject(raw)
            val width = data.optInt("width").takeIf { it > 0 } ?: data.optInt("physicalWidth")
            val height = data.optInt("height").takeIf { it > 0 } ?: data.optInt("physicalHeight")
            if (width > 0 && height > 0) return FullscreenDisplay.baseSize(FullscreenDisplay.Size(width, height), data.optInt("rotation", data.optInt("userRotation")))
        } catch (_: Exception) { }
        val size = primaryDisplaySize()
        return FullscreenDisplay.Size(size[0].coerceAtLeast(240), size[1].coerceAtLeast(240))
    }

    private suspend fun resizeDesktopCapture(size: FullscreenDisplay.Size, fullscreen: Boolean): Boolean = withContext(Dispatchers.Main) {
        val previous = _virtualScreenState.value
        if (previous.width == size.width && previous.height == size.height) {
            engineClient?.configureCanvas(size.width, size.height, false, false)
            return@withContext true
        }
        val surface = frameDispatcher.resizeCaptureSync(size.width, size.height) ?: return@withContext false
        try {
            withContext(Dispatchers.IO) { displayClient().reconfigureDisplay(size.width, size.height, surface, fullscreen) }
            _virtualScreenState.value = previous.copy(width = size.width, height = size.height)
            engineClient?.configureCanvas(size.width, size.height, false, false)
            true
        } catch (e: Exception) {
            val old = frameDispatcher.resizeCaptureSync(previous.width, previous.height)
            if (old != null) withContext(Dispatchers.IO) {
                displayClient().reconfigureDisplay(previous.width, previous.height, old, true)
            }
            SunshineLogManager.log("DISPLAY_MODE", "Desktop resize rolled back: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun restoreFullscreenDisplay(): Boolean {
        val prefs = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE) ?: return true
        val raw = prefs.getString("original", null) ?: return true
        val data = org.json.JSONObject(raw)
        val size = if (data.getInt("width") > 0 && data.getInt("height") > 0)
            FullscreenDisplay.Size(data.getInt("width"), data.getInt("height")) else null
        val before = FullscreenDisplay.Snapshot(size, 0, autoRotate = data.getBoolean("autoRotate"),
            userRotation = data.getInt("userRotation"), fixedRotation = data.getBoolean("fixedRotation"))
        if (!FullscreenDisplay.restore(before, fullscreenBackend())) return false
        check(prefs.edit().remove("original").commit())
        appliedFullscreen = null
        return true
    }

    private fun restoreFullscreenIfIdle() {
        val prefs = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE) ?: return
        if (!prefs.contains("original")) return
        fullscreenScope.launch {
            if (activeSessions.isNotEmpty() || !PrivilegedShell.available() || !_fullscreenBusy.compareAndSet(false, true)) return@launch
            try {
                if (activeSessions.isNotEmpty()) return@launch
                if (restoreFullscreenDisplay()) {
                    SunshineLogManager.log("FULLSCREEN", "Restored original display after all clients exited")
                }
            } catch (e: Exception) { SunshineLogManager.log("FULLSCREEN_ERR", "Display restore failed: ${e.message}") }
            finally { _fullscreenBusy.value = false }
        }
    }

    suspend fun setStreamDisplayMode(mode: StreamDisplayMode): FullscreenDisplay.Result = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext FullscreenDisplay.Result(false, "主机尚未初始化")
        synchronized(_streamDisplayMode) {
            if (!StreamDisplayPreferences(ctx).write(mode)) return@withContext FullscreenDisplay.Result(false, "模式保存失败，请重试")
            _streamDisplayMode.value = mode
        }
        if (mode == StreamDisplayMode.DEFAULT) fullscreenOwner = null
        applyStreamDisplayMode()
    }

    private suspend fun applyStreamDisplayMode(): FullscreenDisplay.Result = withContext(Dispatchers.IO + NonCancellable) {
        displayModeMutex.withLock {
            // A connection can arrive during display restoration or virtual-screen creation.
            // Wait for that operation instead of dropping the saved startup choice.
            while (_fullscreenBusy.value || _virtualScreenState.value.busy) kotlinx.coroutines.delay(50)
            if (!_fullscreenBusy.compareAndSet(false, true)) {
                displayModeChanges.trySend(Unit)
                return@withLock FullscreenDisplay.Result(false, "模式已保存，正在等待显示切换")
            }
            try {
                val mode = _streamDisplayMode.value
                val selected = activeSessions.firstOrNull { it.videoThreadId == fullscreenOwner }
                    ?: activeSessions.firstOrNull()
                if (selected == null) {
                    if (PrivilegedShell.available()) restoreFullscreenDisplay()
                    val size = displayCaptureSize(1, 1)
                    engineClient?.configureCanvas(size[0], size[1], false, false)
                    return@withLock FullscreenDisplay.Result(true, "已保存${if (mode == StreamDisplayMode.DEFAULT) "默认" else "全屏"}模式，下次连接自动应用")
                }
                if (_virtualScreenState.value.active && _virtualScreenState.value.mode == "desktop") {
                    // 零权限本地虚拟屏不支持 shell 改主机分辨率，直接保持当前桌面尺寸。
                    if (noPrivilege()) return@withLock FullscreenDisplay.Result(true, "零权限本地虚拟屏已就绪")
                    val state = _virtualScreenState.value
                    val native = engineClient?.videoSessions()?.firstOrNull { it.threadId == selected.videoThreadId }
                        ?: return@withLock FullscreenDisplay.Result(false, "模式已保存，等待连接准备完成")
                    val snapshot = org.json.JSONObject(displayClient().snapshot())
                    val size = if (mode == StreamDisplayMode.FULLSCREEN) FullscreenDisplay.Size(native.requestedWidth, native.requestedHeight)
                        else if (state.systemDexMirror && state.displayId > 0)
                            FullscreenDisplay.Size(snapshot.optInt("default_dex_width", state.width), snapshot.optInt("default_dex_height", state.height))
                        else primaryDefaultSize()
                    if (mode == StreamDisplayMode.DEFAULT && !restoreFullscreenDisplay())
                        return@withLock FullscreenDisplay.Result(false, "默认模式已保存，但主屏显示设置尚未恢复")
                    if (state.displayId == 0 && mode == StreamDisplayMode.FULLSCREEN) {
                        val fit = FullscreenDisplay.apply(size, fullscreenBackend(rememberOriginal = true)) { activeSessions.isNotEmpty() }
                        if (!fit.applied) return@withLock fit
                    }
                    check(resizeDesktopCapture(size, mode == StreamDisplayMode.FULLSCREEN)) { "独立桌面采集窗口调整失败" }
                    return@withLock FullscreenDisplay.Result(true, "已按 ${size.width} × ${size.height} 串流独立桌面")
                }
                if (mode == StreamDisplayMode.DEFAULT) {
                    val hasOverride = context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE)?.contains("original") == true
                    if (hasOverride && (!PrivilegedShell.available() || !restoreFullscreenDisplay()))
                        return@withLock FullscreenDisplay.Result(false, "默认模式已保存，恢复主机分辨率需要可用的 Shizuku 授权")
                    appliedFullscreen = null
                    check(fullscreenBackendSyncCapture()) { "采集窗口尚未就绪" }
                    return@withLock FullscreenDisplay.Result(true, "已按主机当前分辨率串流")
                }
                val native = engineClient?.videoSessions()?.firstOrNull { it.threadId == selected.videoThreadId }
                    ?: return@withLock FullscreenDisplay.Result(false, "全屏模式已保存，等待连接准备完成")
                // Host session width/height are mutable encoder dimensions. Only the
                // native request retains Moonlight's original size after rotation.
                val request = FullscreenDisplay.Size(native.requestedWidth, native.requestedHeight)
                val identity = listOf(selected.videoThreadId, selected.startTime, request.width.toLong(), request.height.toLong())
                fullscreenOwner = selected.videoThreadId
                if (appliedFullscreen == identity && displayCaptureSize(1, 1).contentEquals(intArrayOf(request.width, request.height)))
                    return@withLock FullscreenDisplay.Result(true, "全屏模式已应用")
                if (!PrivilegedShell.available()) return@withLock FullscreenDisplay.Result(false, "全屏模式已保存；调整主机分辨率需要 Shizuku 授权")
                if (!frameDispatcher.isRunning || virtualDisplay == null)
                    return@withLock FullscreenDisplay.Result(false, "全屏模式已保存，等待屏幕采集就绪")
                val result = FullscreenDisplay.apply(request, fullscreenBackend(rememberOriginal = true)) {
                    _streamDisplayMode.value == StreamDisplayMode.FULLSCREEN &&
                        activeSessions.any { it.videoThreadId == selected.videoThreadId && it.startTime == selected.startTime }
                }
                if (result.applied) appliedFullscreen = identity
                SunshineLogManager.log("FULLSCREEN", result.message)
                result
            } catch (e: Exception) {
                FullscreenDisplay.Result(false, "模式已保存，显示切换未完成：${e.message}")
            } finally {
                _fullscreenBusy.value = false
                if (activeSessions.isEmpty()) restoreFullscreenIfIdle()
            }
        }
    }

    private fun fullscreenBackendSyncCapture(): Boolean {
        val latch = CountDownLatch(1)
        var synced = false
        captureHandler.post {
            try {
                synced = syncCaptureSize()
            } finally { latch.countDown() }
        }
        return latch.await(6, TimeUnit.SECONDS) && synced
    }

    suspend fun fullscreenSession(sessionId: Long): FullscreenDisplay.Result {
        val native = engineClient?.videoSessions()?.firstOrNull { it.sessionId == sessionId }
            ?: return FullscreenDisplay.Result(false, "该连接已结束，请重新选择")
        fullscreenOwner = native.threadId
        appliedFullscreen = null
        return setStreamDisplayMode(StreamDisplayMode.FULLSCREEN)
    }

    fun getPerfSnapshot(): PerfResponse {
        return if (_isStreaming.value) {
            PerfResponse(
                active = true,
                fps = lastFps,
                encodeTimeMs = lastEncodeTimeMs,
                networkLatencyMs = lastNetworkLatencyMs.takeIf { lastNetworkSampleMs > 0 && SystemClock.elapsedRealtime() - lastNetworkSampleMs <= 5000 },
                bitrateKbps = lastBitrate,
                gpuHandoffMs = lastEncodeTimeMs,
                networkProbeIp = lastNetworkProbeIp,
                networkSampleAgeMs = if (lastNetworkSampleMs > 0) SystemClock.elapsedRealtime() - lastNetworkSampleMs else null
            )
        } else {
            PerfResponse(active = false)
        }
    }

    fun restart(): Boolean {
        if (_isStreaming.value || engineClient == null) return false
        SunshineLogManager.log("RESTART", "Requesting idle engine restart")
        triggerAutoRecovery()
        return true
    }

    private var isAudioCaptureInitialized = false
    private val audioCaptureLock = Any()
    @Volatile private var audioCapturing = false
    @Volatile private var audioCaptureEpoch = 0L
    @Volatile private var audioPacketFloats = 0
    @Volatile private var audioBufferMs = 40
    @Volatile private var audioBuffer = PcmAudioBuffer(40 * 96)
    private var audioCaptureThread: Thread? = null

    fun diagnostics(): DiagnosticsResponse {
        val currentAudioBuffer = audioBuffer
        val (queued, dropped) = currentAudioBuffer.snapshot()
        return DiagnosticsResponse(
            frames = frameDispatcher.diagnostics(),
            audio = AudioDiagnostics(audioCapturing, currentAudioBuffer.capacityFloats / 96, queued / 96.0, dropped / 96.0),
            perf = getPerfSnapshot(),
            capabilities = mapOf(
                "frame_pacing" to "per-client; monotonic; missed deadlines skipped",
                "frame_buffer" to "latest SurfaceTexture; at most one pending dispatch",
                "gpu_handoff_ms" to "GL draw and encoder surface swap; not hardware encode latency",
                "network_latency_ms" to "ICMP probe to detected peer; null if unavailable or stale",
                "packet_loss_percent" to "unavailable: native client-feedback callback required",
                "bitrate_kbps" to "aggregate UID TX including audio, FEC and API traffic; not per-client bitrate",
                "session_codec" to "native negotiated codec and MediaCodec component name",
                "encoder_latency" to "video.submit_to_output_p95_ms: bounded 256 output samples; EGL submission to output, not end-to-end latency",
                "dynamic_bitrate" to "per-session manual and opt-in feedback adaptation within negotiated video/FEC budget; driver result reported",
                "adaptive_fec" to "opt-in loss-report backoff and cautious control-RTT probe; manual FEC 0..35, increased redundancy waits for encoder acceptance",
                "fec_feedback" to "best-effort reports for recovered/incomplete blocks, not complete loss coverage or a global loss percentage",
                "control_rtt" to "per-session reliable ENet control RTT; null after five seconds, not video end-to-end latency",
                "audio_buffer" to "bounded stereo PCM; drops oldest on overflow; cleared on idle",
                "wifi_low_latency" to "requested only while streaming; OS/device decides effectiveness"
            ),
            video = engineClient?.videoSessions().orEmpty(),
            encoderError = _encoderError.value
        )
    }

    private fun resumeAudioCapture(record: AudioRecord) = synchronized(audioCaptureLock) {
        if (audioCapturing) return@synchronized
        if (audioCaptureThread?.isAlive == true) {
            SunshineLogManager.log("AUDIO_WARN", "Previous audio reader is still stopping")
            return@synchronized
        }
        audioBuffer.clear()
        record.startRecording()
        val epoch = ++audioCaptureEpoch
        audioCapturing = true
        audioCaptureThread = Thread({
            val chunk = FloatArray(960) // 10ms, 48kHz stereo; independent of client packet size.
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                while (audioCapturing && audioCaptureEpoch == epoch) {
                    val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (read > 0 && audioCaptureEpoch == epoch) audioBuffer.write(chunk, read)
                    else if (read < 0) break
                    else Thread.sleep(2)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                SunshineLogManager.log("AUDIO_WARN", "Audio reader stopped: ${e.message}")
            } finally {
                if (audioCaptureEpoch == epoch) audioCapturing = false
            }
        }, "Sunshine-Audio-Capture").apply { isDaemon = true; start() }
    }

    private fun pauseAudioCapture() = synchronized(audioCaptureLock) {
        audioCapturing = false
        ++audioCaptureEpoch
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioCaptureThread?.interrupt()
        try { audioCaptureThread?.join(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        audioBuffer.clear()
    }

    // Audio capture via AudioPlaybackCapture

    // ==================== 沧海集成：录音权限自授予 + HDMI 音量独立控制 ====================
    // 有线显示器自带音响时，系统默认把媒体音频路由到 HDMI；串流桌面时声音会从
    // 外接屏播放。cmd audio 支持按设备独立调音量：把 HDMI(native type 400) 的
    // STREAM_MUSIC(3) 音量置 0（不影响手机扬声器），串流结束后恢复原值。

    private var savedHdmiMediaVolume: Int? = null
    private val HDMI_NATIVE_DEVICE_TYPE = 400

    private fun ensureAudioPermission() {
        val ctx = context ?: return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!PrivilegedShell.available()) return
        PrivilegedShell.run(arrayOf("pm", "grant", ctx.packageName, android.Manifest.permission.RECORD_AUDIO), 3000)
        SunshineLogManager.log("AUDIO", "RECORD_AUDIO self-grant attempted via Shizuku")
    }

    private fun muteHdmiMediaVolume() {
        if (savedHdmiMediaVolume != null || !PrivilegedShell.available()) return
        val prev = PrivilegedShell.run(arrayOf("cmd", "audio", "get-stream-volume", "3"), 2000)
            ?.lineSequence()?.lastOrNull { it.contains("->") }
            ?.substringAfter("->")?.trim()?.toIntOrNull()
        PrivilegedShell.run(arrayOf("cmd", "audio", "set-device-volume", "3", "0", HDMI_NATIVE_DEVICE_TYPE.toString()), 2000)
        savedHdmiMediaVolume = prev
        SunshineLogManager.log("AUDIO", "HDMI media volume muted during streaming (prev=$prev)")
    }

    private fun restoreHdmiMediaVolume() {
        val prev = savedHdmiMediaVolume ?: return
        savedHdmiMediaVolume = null
        if (!PrivilegedShell.available()) return
        PrivilegedShell.run(arrayOf("cmd", "audio", "set-device-volume", "3", prev.toString(), HDMI_NATIVE_DEVICE_TYPE.toString()), 2000)
        SunshineLogManager.log("AUDIO", "HDMI media volume restored to $prev")
    }

    @Synchronized
    private fun startAudioCapture(packetDuration: Int = 10) {
        val ctx = context ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SunshineLogManager.log("AUDIO_WARN", "Audio permission not granted; streaming video only")
            return
        }
        val duration = packetDuration.takeIf { it in 1..100 } ?: 10
        val samplesPerPacket = (duration * 48000) / 1000
        audioPacketFloats = maxOf(audioPacketFloats, samplesPerPacket * 2)
        if (audioBuffer.capacityFloats < samplesPerPacket * 2) {
            audioBuffer = PcmAudioBuffer(samplesPerPacket * 2)
        }
        if (isAudioCaptureInitialized && audioRecord != null) {
            try {
                resumeAudioCapture(audioRecord!!)
                engineClient?.startAudio(samplesPerPacket)
            } catch (e: RuntimeException) {
                SunshineLogManager.log("AUDIO_WARN", "Cannot resume audio capture: ${e.message}")
            }
            return
        }
        val mp = mediaProjection ?: run {
            SunshineLogManager.log("AUDIO_WARN", "MediaProjection is null, cannot capture audio")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .excludeUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                val sampleRate = 48000
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )

                val bufferSize = if (minBuffer > 0) minBuffer * 2 else 48000 * 4
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord = record
                resumeAudioCapture(record)
                isAudioCaptureInitialized = true
                engineClient?.startAudio(samplesPerPacket)
                SunshineLogManager.log("AUDIO", "Audio playback capture started: 48000Hz stereo float ($samplesPerPacket samples/pkt)")
            } catch (e: Throwable) {
                pauseAudioCapture()
                try { audioRecord?.release() } catch (_: RuntimeException) {}
                audioRecord = null
                isAudioCaptureInitialized = false
                SunshineLogManager.log("AUDIO_ERR", "Failed to start AudioPlaybackCapture: ${e.message}")
            }
        }
    }

    fun readAudio(count: Int, mode: Int): FloatArray {
        val epoch = audioCaptureEpoch
        val requested = count.coerceIn(0, 9600) / 2 * 2
        if (requested == 0 || !audioCapturing) return floatArrayOf()
        if (requested > audioBuffer.capacityFloats) {
            audioPacketFloats = maxOf(audioPacketFloats, requested)
            audioBuffer = PcmAudioBuffer(maxOf(audioBufferMs * 96, requested))
        }
        // Wait on the producer condition, never on AudioRecord and never in a polling loop.
        val timeoutMs = if (mode == AudioRecord.READ_BLOCKING) (requested / 96 + 10).coerceIn(20, 110).toLong() else 0L
        val samples = try { audioBuffer.readPacket(requested, timeoutMs) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); return floatArrayOf() }
        return if (audioCapturing && epoch == audioCaptureEpoch) samples else floatArrayOf()
    }

    @Synchronized
    private fun releaseCapture(stopProjection: Boolean = true) {
        com.aosen.shizukudex.ScreenRegistry.get().onStreamGone()
        LocalMoonlightDisplay.get().release()
        try { virtualScreenClient?.release() } catch (_: Exception) {}
        virtualScreenClient = null
        _virtualScreenState.value = VirtualScreenState()
        frameDispatcher.setVirtualSource(false)
        pauseAudioCapture()
        frameDispatcher.stop()
        virtualDisplay?.release()
        virtualDisplay = null
        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        isAudioCaptureInitialized = false
        val projection = mediaProjection
        mediaProjection = null
        engineClient?.setCaptureAuthorized(false)
        if (stopProjection) try { projection?.stop() } catch (_: Exception) {}
    }

    @Synchronized
    fun releaseAll() {
        onEngineDisconnected()
        releaseCapture()
        captureDisplayManager?.unregisterDisplayListener(captureDisplayListener)
        captureDisplayManager = null
        com.alkaidlab.sunshine.engine.InputBroker.reset()
    }

    fun getLastWidth(): Int = if (lastWidth > 0) lastWidth else 720
    fun getLastHeight(): Int = if (lastHeight > 0) lastHeight else 1600

    // Shizuku Input Injection
    @Volatile private var isBoundViaShizuku = false

    @Synchronized
    fun onShizukuDisconnected() {
        isBoundViaShizuku = false
        remoteWake?.reset()
        inputManager = null
        injectInputMethod = null
    }

    @Synchronized
    fun initShizukuInput() {
        restoreFullscreenIfIdle()
        try {
            val hasShizuku = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }

            if (hasShizuku) {
                val rawBinder = rikka.shizuku.SystemServiceHelper.getSystemService("input")
                val wrappedBinder = rikka.shizuku.ShizukuBinderWrapper(rawBinder)
                val iInputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = iInputManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
                inputManager = asInterfaceMethod.invoke(null, wrappedBinder)

                injectInputMethod = inputManager?.javaClass?.methods?.find {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 2
                }
                isBoundViaShizuku = inputManager != null && injectInputMethod != null
                if (activeSessions.isNotEmpty()) displayModeChanges.trySend(Unit)
                SunshineLogManager.log("SHIZUKU", "InputManager bound via Shizuku successfully (wrapped binder)")
                return
            }

            if (inputManager == null) {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
                val binder = getServiceMethod.invoke(null, "input") as? android.os.IBinder
                if (binder != null) {
                    val iInputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    val asInterfaceMethod = iInputManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
                    inputManager = asInterfaceMethod.invoke(null, binder)

                    injectInputMethod = inputManager?.javaClass?.methods?.find {
                        it.name == "injectInputEvent" && it.parameterTypes.size == 2
                    }
                    SunshineLogManager.log("INPUT", "InputManager bound via standard ServiceManager")
                }
            }
        } catch (e: Exception) {
            SunshineLogManager.log("SHIZUKU_WARN", "Shizuku InputManager init: ${e.message}")
        }
    }

    private fun injectWakeKey(): Boolean {
        if (!isBoundViaShizuku) initShizukuInput()
        if (!isBoundViaShizuku) return false
        val im = inputManager ?: return false
        val method = injectInputMethod ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).all { action ->
                val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_WAKEUP, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                method.invoke(im, event, 0) == true
            }
        } catch (_: Exception) { false }
    }

    // ==============================================================
    // 统一输入路由
    //
    // 画面（建屏）阶段的分叉只发生在 applyStreamDisplayMode()：有权限走
    // VirtualScreenClient.create，零权限走 LocalMoonlightDisplay.ensure。
    // 画面就绪之后，Moonlight 客户端的键鼠/手柄包从 InputBroker 进来，
    // 三种权限共用下面这一份「客户端语义 → 桌面动作」翻译，只有最后一跳
    // deliverMotion / deliverKey 按权限分叉。不要在别处再写第二条派发链路。
    //
    // 注意：本文件在 :engine 与主进程各有一份实例。:engine 那份的
    // virtualScreenState.active 永远是 false，只会经 SunshineEngineBridge
    // 转发出去；所有「消耗事件」的语义判断必须放在 active 分支里，否则
    // 会在转发之前被提前吞掉，主进程永远收不到。
    // ==============================================================

    @Volatile private var inputCursorX = -1f
    @Volatile private var inputCursorY = -1f
    private var lastRemoteRightClickMs = 0L
    private val inputUiHandler = Handler(Looper.getMainLooper())

    /** 唯一的最后一跳分叉：零权限投本地虚拟屏帧队列，有权限投 display controller。 */
    private fun deliverMotion(event: MotionEvent) {
        if (noPrivilege()) {
            LocalMoonlightDisplay.get().dispatchRemoteMotion(event)
            return
        }
        try { virtualScreenClient?.motion(event) } catch (e: Exception) {
            SunshineLogManager.log("VIRTUAL_INPUT_ERR", e.message.orEmpty())
        }
    }

    private fun deliverKey(keyCode: Int, isDown: Boolean, modifiers: Int) {
        if (noPrivilege()) {
            LocalMoonlightDisplay.get().dispatchRemoteKey(keyCode, isDown, modifiers)
            return
        }
        try { virtualScreenClient?.key(keyCode, isDown, modifiers) } catch (e: Exception) {
            SunshineLogManager.log("VIRTUAL_INPUT_ERR", e.message.orEmpty())
        }
    }

    /** 内部自造的 MotionEvent 用完即回收，不依赖下游是否 recycle（跨进程时下游只拿到副本）。 */
    private fun injectFrame(event: MotionEvent) {
        try { deliverMotion(event) } finally { event.recycle() }
    }

    private fun clickFrame(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
    }

    private fun buttonFrame(action: Int, x: Float, y: Float, button: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, button,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    /** 客户端右键在协议里就是 KEYCODE_BACK；这里统一翻译成桌面右键，不再层层各补一次。 */
    private fun translateRemoteKey(keyCode: Int, isDown: Boolean, modifiers: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK || modifiers and MOUSE_RIGHT_FLAG == 0) return false
        if (!isDown) return true   // 抬起吞掉，一次右键只弹一次菜单
        val now = SystemClock.uptimeMillis()
        if (now - lastRemoteRightClickMs < 260L) return true
        lastRemoteRightClickMs = now
        val x = inputCursorX
        val y = inputCursorY
        if (x < 0f || y < 0f) return false   // 还没移动过鼠标：落回原返回键语义
        if (noPrivilege()) {
            LocalMoonlightDisplay.get().dispatchRemoteButton(x, y, MotionEvent.BUTTON_SECONDARY)
        } else {
            // 系统链路上 WebView 只认真实右键或长按，这里在光标处补一次长按。
            injectFrame(clickFrame(MotionEvent.ACTION_DOWN, x, y))
            val hold = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 120L
            inputUiHandler.postDelayed({ injectFrame(clickFrame(MotionEvent.ACTION_UP, x, y)) }, hold)
        }
        return true
    }

    /** 中键：零权限派按钮帧给桌面视图树，有权限按标准 BUTTON_TERTIARY 注入系统。 */
    private fun deliverMiddleClick(x: Float, y: Float) {
        if (noPrivilege()) {
            LocalMoonlightDisplay.get().dispatchRemoteButton(x, y, MotionEvent.BUTTON_TERTIARY)
            return
        }
        injectFrame(buttonFrame(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_TERTIARY))
        injectFrame(buttonFrame(MotionEvent.ACTION_BUTTON_RELEASE, x, y, MotionEvent.BUTTON_TERTIARY))
    }

    fun injectMotionEvent(event: MotionEvent) = injectMotion(event, false)

    /**
     * 外设大脑把「物理外设的一帧」搬到串流屏。
     *
     * 物理事件在大脑入口已经记过一条（含系统原值与落点），这里绝不再记一遍，
     * 否则一次点击会出现三四行重复记录。
     */
    fun injectMotionEventFromPeripheral(event: MotionEvent) = injectMotion(event, true)

    private fun injectMotion(event: MotionEvent, fromPeripheral: Boolean) {
        if (!fromPeripheral) {
            try { com.aosen.shizukudex.InputTrace.system("客户端", event) } catch (_: Throwable) {}
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            try { com.aosen.shizukudex.MainActivity.onExternalInteractionOrIme() } catch (_: Throwable) {}
        }
        if (SunshineEngineBridge.injectMotion(event)) return
        if (_virtualScreenState.value.busy && !preparingLocalDex) return
        if (_virtualScreenState.value.active) {
            // Shizuku overlay 光标只在特权链路上画一次；:engine 那份实例不进这个分支，
            // 零权限本地屏也不需要它（桌面自绘光标）。
            if (!noPrivilege()) drawRemoteCursor(event)
            if (_virtualScreenState.value.systemDexMirror && sourceDisplayId == Display.DEFAULT_DISPLAY) onRemoteInput()
            // 主进程唯一落地：先记光标，再统一翻译，最后按权限分叉。
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_HOVER_MOVE -> { inputCursorX = event.x; inputCursorY = event.y }
            }
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) {
                deliverMiddleClick(event.x, event.y)
                return
            }
            deliverMotion(event)
            return
        }
        onRemoteInput()
        // 没有（或还没建好）串流屏时，事件只能落到「默认屏 0」= 手机主屏。
        // 那正是「鼠标明明在 Moonlight，长按却触发了手机的长按识屏/AI」的来源：
        // 到这里没有真实 displayId 就丢掉，并且必须在面板上看得见，绝不注入手机。
        val targetId = com.aosen.shizukudex.ScreenRegistry.get()
            .realIdOf(com.aosen.shizukudex.StreamTarget.UI_ID)
        if (targetId <= 0) {
            try { com.aosen.shizukudex.InputTrace.leak("注入", event, "串流屏未就绪，已丢弃") } catch (_: Throwable) {}
            return
        }
        if (com.aosen.shizukudex.ShizukuCmd.injectMotionEvent(event, targetId)) return
        try { com.aosen.shizukudex.InputTrace.leak("注入", event, "注入通道不可用，已丢弃") } catch (_: Throwable) {}
    }

    /**
     * PC 远程鼠标的软件指针：Moonlight 客户端隐藏本地指针、虚拟屏也不渲染硬件
     * 指针图标，因此让 Shizuku shell 进程在虚拟屏上加 overlay 窗口画白色箭头。
     * 事件坐标即客户端分辨率坐标，虚拟屏已按客户端请求分辨率创建，直接可用。
     */
    private fun drawRemoteCursor(event: MotionEvent) {
        val targetDisplay = if (_virtualScreenState.value.active && _virtualScreenState.value.displayId > 0) {
            _virtualScreenState.value.displayId
        } else {
            sourceDisplayId
        }
        if (targetDisplay <= 0) return
        val context = context ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                RemoteCursorOverlay.show(context, targetDisplay, event.x, event.y)
            MotionEvent.ACTION_HOVER_EXIT ->
                RemoteCursorOverlay.hideOnly(targetDisplay)
        }
    }

    fun handleTouchPacket(action: Int, pointerId: Int, flags: Int, x: Float, y: Float, pressure: Float, contactArea: Float) {
        com.alkaidlab.sunshine.engine.InputBroker.handleTouchPacket(action, flags, pointerId.toLong(), x, y, pressure, contactArea, 0f)
    }

    fun handleAbsMouseMove(x: Float, y: Float, width: Float, height: Float) {
        com.alkaidlab.sunshine.engine.InputBroker.handleAbsMouseMovePacket(x, y, width, height)
    }

    fun handleMouseButton(button: Int, isDown: Boolean) {
        com.alkaidlab.sunshine.engine.InputBroker.handleLeftMouseButton(!isDown)
    }

    fun handleKeyboardEvent(keyCode: Int, isDown: Boolean, modifiers: Int) {
        try { com.aosen.shizukudex.InputTrace.key("客户端", keyCode, isDown, modifiers) } catch (_: Throwable) {}
        if (_virtualScreenState.value.active && keyCode == KeyEvent.KEYCODE_ESCAPE &&
            modifiers and KeyEvent.META_CTRL_ON != 0 && modifiers and KeyEvent.META_ALT_ON != 0) {
            if (isDown) fullscreenScope.launch(Dispatchers.Main) { exitVirtualScreen() }
            if (isDown) SunshineLogManager.log("VIRTUAL_EXIT", "Requested by Ctrl+Alt+Escape")
            return
        }
        if (SunshineEngineBridge.injectKeyboard(keyCode, isDown, modifiers)) return
        if (_virtualScreenState.value.busy && !preparingLocalDex) return
        if (_virtualScreenState.value.active) {
            if (_virtualScreenState.value.systemDexMirror && sourceDisplayId == Display.DEFAULT_DISPLAY) onRemoteInput()
            // 主进程唯一落地：统一翻译客户端语义，再按权限分叉最后一跳。
            if (translateRemoteKey(keyCode, isDown, modifiers)) return
            deliverKey(keyCode, isDown, modifiers and MOUSE_RIGHT_FLAG.inv())
            return
        }
        onRemoteInput()
        // 同 motion：没有真实串流屏就绝不注入默认屏（0=手机），否则一次按键会落到手机上。
        val keyTarget = com.aosen.shizukudex.ScreenRegistry.get()
            .realIdOf(com.aosen.shizukudex.StreamTarget.UI_ID)
        if (keyTarget <= 0) {
            try { com.aosen.shizukudex.InputTrace.leakKey("注入", keyCode, isDown, modifiers) } catch (_: Throwable) {}
            return
        }
        val now = SystemClock.uptimeMillis()
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        try {
            com.aosen.shizukudex.ShizukuCmd.injectKeyEvent(
                KeyEvent(now, now, action, keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0),
                keyTarget)
        } catch (_: Throwable) {}
    }
}
