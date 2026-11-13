package com.aosen.shizukudex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 渲染层（窗口层）清单，按「从上到下」排列。
 *
 * <p><b>这是「静态层清单 + 调试开关」，不是系统级读取，也不是实时窗口栈快照</b>：
 * 没有调用 dumpsys / SurfaceFlinger / WMS；{@code order} 是人工设定并按真机实测校正的
 * 相对顺序，不保证与 WMS 运行时的 layer 分配完全一致。绿点 /「未屏蔽」只表示
 * 用户没有屏蔽该层，不代表窗口此刻真的挂上了。能确证的层会额外输出 {@code mounted}
 * （真实挂载探测）；无法确证的系统层 / 外部应用窗口只做示意展示，不可屏蔽。
 *
 * <p>真机实测（Root / Moonlight 虚拟屏，2026-11-11）：底部可触摸 Dock 条带在最上，
 * 远程光标层在其下、外部应用窗口之上；即 Dock &gt; 光标 &gt; 应用。
 */
public final class RenderLayerRegistry {

    public interface Switch {
        void apply(boolean on);
    }

    public static final class Layer {
        public final String id;
        public final String name;
        public final String desc;
        public final int order;          // 数字越大越靠上
        public final boolean controllable;
        public boolean enabled = true;
        public final Switch sw;

        Layer(String id, String name, String desc, int order, boolean controllable, Switch sw) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.order = order;
            this.controllable = controllable;
            this.sw = sw;
        }
    }

    private static final List<Layer> LAYERS = new ArrayList<>();

    static {
        // —— 串流虚拟屏上的层，按真机实测的相对顺序；非系统级读取 ——
        // 这一层在 Root/Moonlight 通道根本不创建窗口，不应混在真实栈里，压到末尾仅作辨识对照。
        LAYERS.add(new Layer("aosen_pointer", "沧海指针层（仅 DeX 路径，本屏不挂载）",
                "历史遗留层：只有 DeXPresentation 路径会调用；纯 Root/Moonlight 通道不创建此窗口。"
                + "本版图标改为「绿色方块 + 白 A」用于辨识。当前实际在画指针的是 Sunshine 远程光标层",
                50, true, on -> AosenPointerOverlay.setUserBlocked(!on)));

        LAYERS.add(new Layer("sunshine_cursor", "Sunshine 远程光标",
                "串流服务自绘指针 · TYPE_APPLICATION_OVERLAY(2038) 全屏 · NOT_TOUCHABLE · 纯 View 不声明 touchableRegion",
                95, true, on -> com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.setUserBlocked(!on)));

        LAYERS.add(new Layer("dexhome_dock", "应用台 / Dock 虚拟窗",
                "Root：底部条带 · 可触摸 · 应用区无覆盖窗（全屏模态态临时拉回整屏）；非 Root：整屏 NOT_TOUCHABLE 原样",
                96, true, on -> DexHomeActivity.setDockLayerEnabled(on)));

        LAYERS.add(new Layer("dexhome_probe", "透明探测条",
                "DexHome 探测窗口 · 虚拟屏 addView · NOT_TOUCHABLE · 悬停唤回任务栏/标题栏",
                85, false, null));

        LAYERS.add(new Layer("system_spy", "三星 PointerEventDispatcherOverlay",
                "系统 SPY 层 · TRUSTED_OVERLAY · 不参与遮挡判定",
                80, false, null));

        LAYERS.add(new Layer("system_nav", "虚拟屏导航栏",
                "系统 NavigationBar · TRUSTED_OVERLAY",
                75, false, null));

        LAYERS.add(new Layer("app_window", "外部安卓应用窗口",
                "点击的目标窗口 · BLOCK_UNTRUSTED，被遮挡时只收到 CANCEL",
                70, false, null));

        LAYERS.add(new Layer("cast_home", "沧海虚拟屏桌面",
                "CastHomeActivity 桌面窗口（最底层内容）",
                65, false, null));

        LAYERS.add(new Layer("phone_debug", "手机端调试浮窗",
                "手机主屏上的调试日志入口，不覆盖串流屏",
                60, false, null));
    }

    private RenderLayerRegistry() {}

    private static List<Layer> sorted() {
        List<Layer> copy = new ArrayList<>(LAYERS);
        Collections.sort(copy, Comparator.comparingInt((Layer l) -> l.order).reversed());
        return copy;
    }

    /** 只对自己能确证的层回报真实挂载状态；无法确证的层不输出该字段，避免误导。 */
    private static Boolean probeMounted(String id) {
        if ("aosen_pointer".equals(id)) return AosenPointerOverlay.isShowing();
        if ("sunshine_cursor".equals(id)) {
            return com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.isShowing();
        }
        return null;
    }

    public static synchronized String json() {
        JSONArray arr = new JSONArray();
        for (Layer l : sorted()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", l.id);
                o.put("name", l.name);
                o.put("desc", l.desc);
                o.put("order", l.order);
                o.put("controllable", l.controllable);
                o.put("enabled", l.enabled);
                Boolean mounted = probeMounted(l.id);
                if (mounted != null) {
                    o.put("mountedKnown", true);
                    o.put("mounted", mounted.booleanValue());
                }
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr.toString();
    }

    /** 返回是否真的切换成功；不可屏蔽的层返回 false。 */
    public static synchronized boolean setEnabled(String id, boolean on) {
        for (Layer l : LAYERS) {
            if (!l.id.equals(id)) continue;
            if (!l.controllable || l.sw == null) return false;
            l.enabled = on;
            try {
                l.sw.apply(on);
            } catch (Throwable t) {
                android.util.Log.w("CanghaiRenderLayer", "切换失败 " + id + ": " + t);
                return false;
            }
            return true;
        }
        return false;
    }

    public static synchronized void resetAll() {
        for (Layer l : LAYERS) {
            if (l.controllable && !l.enabled) setEnabled(l.id, true);
        }
    }
}
