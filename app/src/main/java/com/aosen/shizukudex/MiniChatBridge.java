package com.aosen.shizukudex;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiniChat 局域网大厅（原 Windows_Project/minichat，纯 C 零依赖）在 Android 的宿主封装。
 *
 * 服务跑在 .so 里的后台 pthread。对外只暴露「短地址 + 6 位配对码」：
 * http://&lt;ip&gt;:&lt;port&gt; 打开后输配对码，成功后服务端种下长随机 token 的
 * Cookie，之后自动放行。长 token 只走 --token 和 Cookie，用户永远看不见、不用抄。
 * 配对码持久化在 &lt;filesDir&gt;/minichat/pin，本机 127.0.0.1 免配对。
 *
 * 不做公钥私钥，也不把口令塞进网址：二维码只要编码短地址，换设备不用重新生成。
 * 数据目录 &lt;filesDir&gt;/minichat/（Chat-Files/、instance.id、lan.port 都在这里）。
 */
public final class MiniChatBridge {
    private static final String TAG = "MiniChatBridge";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile boolean libOk = false;
    private static volatile int port = -1;
    private static volatile String token = null;
    private static volatile String pin = null;

    static {
        try {
            System.loadLibrary("minichat");
            libOk = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary(minichat) 失败，lan 命名空间不可用", t);
        }
    }

    private static native int nativeStart(String dir, String token, String pin);
    private static native int nativePort();
    private static native int nativeReady();

    private MiniChatBridge() {}

    public static int port() {
        if (port < 0 && libOk) {
            int p = nativePort();
            if (p > 0) port = p;
        }
        return port > 0 ? port : 53317;
    }

    /** 本机地址，免口令；App 内 iframe 和手机浏览器用这个。
     *  根路径就是大厅页，不再带 /__chat__/ 前缀——人只念 IP 和端口。 */
    public static String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    public static boolean ready() {
        return libOk && nativeReady() == 1;
    }

    private static File dataDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "minichat");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建数据目录 " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** 首次启动生成一个随机口令并持久化，保证邀请链接跨重启仍然有效。 */
    public static synchronized String token(Context ctx) {
        if (token != null) return token;
        File f = new File(dataDir(ctx), "token");
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = in.read(b);
                    if (n > 0) {
                        String s = new String(b, 0, n, "UTF-8").trim();
                        if (s.length() >= 8) { token = s; return token; }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 token 失败，将重新生成", t);
        }
        byte[] rnd = new byte[12];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder();
        for (byte x : rnd) sb.append(String.format("%02x", x & 0xff));
        String s = sb.toString();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "写入 token 失败，本次仍可用但重启会变", t);
        }
        token = s;
        return token;
    }

    /** 给人看的 6 位配对码，首次启动生成并持久化，重启不变。 */
    public static synchronized String pairCode(Context ctx) {
        if (pin != null) return pin;
        File f = new File(dataDir(ctx), "pin");
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = in.read(b);
                    if (n > 0) {
                        String s = new String(b, 0, n, "UTF-8").trim();
                        if (s.matches("\\d{6}")) { pin = s; return pin; }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取配对码失败，将重新生成", t);
        }
        String s = String.valueOf(100000 + new SecureRandom().nextInt(900000));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "写入配对码失败，本次仍可用但重启会变", t);
        }
        pin = s;
        return pin;
    }

    /** 取一个局域网 IPv4；没有 Wi-Fi / 以太网时返回 null。 */
    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns != null && ns.hasMoreElements()) {
                NetworkInterface ni = ns.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()
                            && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "取局域网 IP 失败", t);
        }
        return null;
    }

    /** 分享给其它设备的短地址：不带任何口令和路径，二维码好扫、手输也不费劲。 */
    public static String lanUrl(Context ctx) {
        String ip = lanIp();
        if (ip == null) return null;
        return "http://" + ip + ":" + port();
    }

    /** 幂等；返回实际监听端口，失败返回 -1。端口被占用时 native 会向后试 21 个。 */
    public static int start(Context ctx) {
        if (!libOk) return -1;
        int p = nativeStart(dataDir(ctx).getAbsolutePath(), token(ctx), pairCode(ctx));
        if (p > 0) port = p;
        return p;
    }

    /** WebView 挂桥时异步自启，不阻塞 UI，也不阻塞 JS 桥线程。 */
    public static void ensureStartedAsync(final Context ctx) {
        if (!STARTED.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            int p = start(app);
            if (p > 0) {
                String lan = lanUrl(app);
                String code = pairCode(app);
                if (lan != null) CanghaiCore.publishLanUrl(lan, code);
                Log.i(TAG, "MiniChat 已启动: " + url() + "  局域网: " + lan + " 配对码: " + code);
                CanghaiErrorCollector.logExt("[MiniChat] 大厅已启动 本机=" + url()
                        + " 局域网地址=" + (lan != null ? lan : "未取到局域网IP")
                        + " 配对码=" + code + "（其它设备打开地址后输这 6 位数字即可）");
            } else {
                Log.w(TAG, "MiniChat 启动失败 p=" + p);
                CanghaiErrorCollector.logExt("[MiniChat] 启动失败 p=" + p
                        + "（检查 libminichat.so 是否打进包、端口是否被占）");
            }
        }, "minichat-start").start();
    }

    /** 借外部浏览器打开本机大厅（免口令，最稳）。 */
    public static boolean open(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "open 失败", t);
            return false;
        }
    }
}
