package com.alkaidlab.sunshine.engine;

import android.view.Surface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import com.alkaidlab.sunshine.service.SunshineEngineBridge;

/** Java entry points and callbacks for this project's Android Sunshine backend. */
public final class NativeServer {
    private NativeServer() {}

    private static volatile boolean sLoaded = false;
    private static volatile String sLoadError = null;

    /*
     * 原生串流内核是预编译的 libsunshine.so，里面用了 boost::asio 的 thread_local 静态成员。
     * 老系统（实测 Android 9）的 linker 不支持 dlopen 库里的 ELF TLS 符号，会抛
     *   UnsatisfiedLinkError: dlopen failed: unsupported ELF TLS symbol ...
     * 以前这里直接 loadLibrary，一抛就是 Error，把 :engine 进程打死 → 界面还没出来就闪退。
     * 现在降级成「记录失败原因 + 标记不可用」，让上层有机会优雅跳过串流功能。
     */
    static {
        try {
            System.loadLibrary("crypto");
            System.loadLibrary("ssl");
            System.loadLibrary("sunshine");
            sLoaded = true;
        } catch (Throwable t) {
            sLoadError = String.valueOf(t.getMessage());
            android.util.Log.e("NativeServer", "原生串流内核加载失败: " + sLoadError);
        }
    }

    /** 原生内核是否可用（false 表示这台设备跑不了串流，但界面功能仍可正常用）。 */
    public static boolean available() { return sLoaded; }

    public static String loadError() { return sLoadError; }

    /** 原生调用前的统一闸门：不可用时抛可被上层 catch 的运行时异常，而不是让进程直接死。 */
    public static void requireLoaded() {
        if (!sLoaded) {
            throw new IllegalStateException("当前系统不支持原生串流内核: " + sLoadError);
        }
    }

    public static native void enableH265();
    public static native boolean exitServer();
    public static native void setCertPath(String path);
    public static native void setFileStatePath(String path);
    public static native void setPkeyPath(String path);
    public static native void setSunshineName(String name);
    public static native void start();
    public static native void startAudioRecording(Object source, int samplesPerPacket);
    public static native void submitPin(String pin);
    public static native void cleanup();
    public static native void configureVideo(int minimum, int maximum, int maxFps);
    public static native void configureCanvas(int width, int height, boolean followOrientation, int sourceFps, boolean sourceCanvas);
    public static native void configureEncoders(String avc, String hevc, String[] avcNames, String[] hevcNames);
    public static native String videoSnapshot();
    public static native boolean controlVideo(long sessionId, int operation, int value);
    public static String getAppCatalog() { return SunshineEngineBridge.appCatalog(); }
    public static int getAvcProfile(String encoderName) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() || !codec.getName().equals(encoderName)) continue;
                MediaCodecInfo.CodecProfileLevel[] levels = codec.getCapabilitiesForType("video/avc").profileLevels;
                int[] preferred = {MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline};
                for (int profile : preferred) {
                    for (MediaCodecInfo.CodecProfileLevel level : levels) {
                        if (level.profile == profile) return profile;
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return 0;
    }
    public static boolean prepareApp(int appId) { return SunshineEngineBridge.prepareApp(appId); }
    public static int getBitrateMode(String encoderName, String mime) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder() && codec.getName().equals(encoderName) &&
                        codec.getCapabilitiesForType(mime).getEncoderCapabilities().isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
                }
            }
        } catch (RuntimeException ignored) { }
        return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    }
    public static void onPinRequested() { SunshineEngineBridge.pinRequested(); }
    public static void onRemoteInput() { SunshineEngineBridge.remoteInput(); }
    public static int setWorkerPriority(int priority) {
        try { android.os.Process.setThreadPriority(priority); } catch (RuntimeException ignored) { }
        return android.os.Process.getThreadPriority(android.os.Process.myTid());
    }
    public static void onVideoFrameProgress(long threadId, long presentationTimeUs, boolean released) {
        SunshineEngineBridge.videoFrameProgress(threadId, presentationTimeUs, released);
    }
    public static void createVirtualDisplay(int width, int height, int fps, int audioMs,
            Surface surface, boolean audio, int appId, String clientId, String clientAddress) {
        SunshineEngineBridge.streamStarted(width, height, fps, audioMs, surface, audio, appId, clientId, clientAddress);
    }
    public static void stopVirtualDisplay() { SunshineEngineBridge.streamStopped(); }
    public static boolean pauseVirtualDisplay() { return SunshineEngineBridge.streamPaused(); }
    public static boolean resizeVirtualDisplay(int width, int height, Surface surface) {
        return SunshineEngineBridge.streamResized(width, height, surface);
    }
    public static void showEncoderError(String message) { SunshineEngineBridge.encoderError(message); }
    public static void onClientPaired(String client) { SunshineEngineBridge.clientDiscovered(client); }
    public static void onServerUuid(String uuid) { SunshineEngineBridge.serverUuid(uuid); }
}
