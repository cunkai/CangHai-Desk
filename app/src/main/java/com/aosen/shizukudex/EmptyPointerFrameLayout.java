package com.aosen.shizukudex;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 统一光标与交互分发容器：
 * 1. 利用 WebView 原生 CSS 机制自动切换光标（链接/按钮自动变手型，输入框变光标）；
 * 2. 默认箭头使用自绘纯净白箭头（告别三星大黑箭头），零 CPU 轮询硬件加速；
 * 3. 支持透明悬浮窗动态触摸区域（Dock 栏常驻全屏，非 Dock 区域原生穿透给下层应用）。
 */
public class EmptyPointerFrameLayout extends FrameLayout {
    private static final String TAG = "EmptyPointerFrameLayout";

    public interface TouchableInsetsProvider {
        boolean isWholeWindowTouchable();
        Rect getTouchableRect();
        default java.util.List<Rect> getTouchableRects() { return null; }
        int getFallbackDockHeight();
    }

    private static volatile PointerIcon sEmptyPointerIcon;
    private static volatile PointerIcon sCachedThemedIcon;
    private static volatile int sCachedStyle = -1;
    private static volatile float sCachedScale = -1f;

    private TouchableInsetsProvider mInsetsProvider;
    private Object mInsetsListenerProxy;

    private static final java.util.Map<Integer, PointerIcon> sDisplayThemedIcons = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, Integer> sDisplayStyles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, Float> sDisplayScales = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, String> sDisplaySvgs = new java.util.concurrent.ConcurrentHashMap<>();

    public static PointerIcon getEmptyPointerIcon(Context context) {
        if (sEmptyPointerIcon == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            synchronized (EmptyPointerFrameLayout.class) {
                if (sEmptyPointerIcon == null) {
                    try {
                        Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                        sEmptyPointerIcon = PointerIcon.create(bmp, 0f, 0f);
                    } catch (Throwable ignored) {
                        try {
                            sEmptyPointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL);
                        } catch (Throwable ignored2) {}
                    }
                }
            }
        }
        return sEmptyPointerIcon;
    }

    public static PointerIcon getThemedPointerIcon(Context context) {
        return getThemedPointerIcon(context, 0);
    }

    public static PointerIcon getThemedPointerIcon(Context context, int displayId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || context == null) return null;
        int targetDisp = displayId;
        if (targetDisp <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                android.view.Display d = context.getDisplay();
                if (d != null) targetDisp = d.getDisplayId();
            } catch (Throwable ignored) {}
        }
        int style = com.alkaidlab.sunshine.service.MouseCursorPrefs.getStyleForDisplay(context, targetDisp);
        float scale = com.alkaidlab.sunshine.service.MouseCursorPrefs.getScaleForDisplay(context, targetDisp);

        PointerIcon cached = sDisplayThemedIcons.get(targetDisp);
        Integer cStyle = sDisplayStyles.get(targetDisp);
        Float cScale = sDisplayScales.get(targetDisp);
        String cSvg = sDisplaySvgs.get(targetDisp);
        String curSvg = (style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG)
                ? com.alkaidlab.sunshine.service.MouseCursorPrefs.getCustomSvgForDisplay(context, targetDisp)
                : null;

        if (cached != null && cStyle != null && cStyle == style && cScale != null && Math.abs(cScale - scale) < 0.01f
                && ((curSvg == null && cSvg == null) || (curSvg != null && curSvg.equals(cSvg)))) {
            return cached;
        }

        try {
            PointerIcon icon;
            if (style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG) {
                String svg = curSvg;
                Path path = null;
                if (svg != null && !svg.trim().isEmpty()) {
                    try {
                        path = androidx.core.graphics.PathParser.createPathFromPathData(svg.trim());
                    } catch (Throwable ignored) {}
                }
                if (path != null) {
                    RectF bounds = new RectF();
                    path.computeBounds(bounds, true);
                    float pw = Math.max(1f, bounds.width());
                    float ph = Math.max(1f, bounds.height());
                    float s = Math.max(0.4f, Math.min(6.0f, scale));
                    float pad = 4f * s;
                    int bmpW = Math.max(16, Math.min(256, Math.round(pw * s + pad * 2)));
                    int bmpH = Math.max(16, Math.min(256, Math.round(ph * s + pad * 2)));
                    Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);

                    kotlin.Pair<Float, Float> hot = com.alkaidlab.sunshine.service.MouseCursorPrefs.getCustomHotspotForDisplay(context, targetDisp);
                    float hotPctX = (hot != null) ? (hot.getFirst() / 100f) : 0f;
                    float hotPctY = (hot != null) ? (hot.getSecond() / 100f) : 0f;
                    float hotX = pad + (pw * s * hotPctX);
                    float hotY = pad + (ph * s * hotPctY);

                    c.save();
                    c.translate(pad - bounds.left * s, pad - bounds.top * s);
                    c.scale(s, s);

                    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fill.setColor(Color.WHITE);
                    fill.setStyle(Paint.Style.FILL);

                    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                    stroke.setColor(Color.rgb(15, 20, 30));
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(2.2f);
                    stroke.setStrokeJoin(Paint.Join.ROUND);

                    c.drawPath(path, fill);
                    c.drawPath(path, stroke);
                    c.restore();

                    float clampedHotX = Math.max(0f, Math.min(bmpW - 1f, hotX));
                    float clampedHotY = Math.max(0f, Math.min(bmpH - 1f, hotY));
                    icon = PointerIcon.create(bmp, clampedHotX, clampedHotY);
                } else {
                    int baseSize = Math.max(16, Math.round(20 * scale));
                    Bitmap bmp = Bitmap.createBitmap(baseSize, baseSize, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fill.setColor(Color.argb(180, 31, 191, 168));
                    fill.setStyle(Paint.Style.FILL);
                    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                    stroke.setColor(Color.rgb(15, 20, 30));
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setStrokeWidth(2.2f);
                    float r = (baseSize - 4f) / 2f;
                    c.drawCircle(baseSize / 2f, baseSize / 2f, r, fill);
                    c.drawCircle(baseSize / 2f, baseSize / 2f, r, stroke);
                    icon = PointerIcon.create(bmp, baseSize / 2f, baseSize / 2f);
                }
            } else if (style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_BLOCK) {
                int baseSize = Math.max(16, Math.round(18 * scale));
                Bitmap bmp = Bitmap.createBitmap(baseSize, baseSize, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(Color.WHITE);
                fill.setStyle(Paint.Style.FILL);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setColor(Color.rgb(15, 20, 30));
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2.5f);
                c.drawRect(2f, 2f, baseSize - 2f, baseSize - 2f, fill);
                c.drawRect(2f, 2f, baseSize - 2f, baseSize - 2f, stroke);
                icon = PointerIcon.create(bmp, baseSize / 2f, baseSize / 2f);
            } else if (style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_DOT) {
                int baseSize = Math.max(16, Math.round(18 * scale));
                Bitmap bmp = Bitmap.createBitmap(baseSize, baseSize, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(Color.WHITE);
                fill.setStyle(Paint.Style.FILL);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setColor(Color.rgb(15, 20, 30));
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2.5f);
                float radius = (baseSize - 4f) / 2f;
                c.drawCircle(baseSize / 2f, baseSize / 2f, radius, fill);
                c.drawCircle(baseSize / 2f, baseSize / 2f, radius, stroke);
                icon = PointerIcon.create(bmp, baseSize / 2f, baseSize / 2f);
            } else {
                // 经典白箭头（带精致深色描边），彻底替换系统大黑箭头
                float s = Math.max(0.7f, Math.min(3.0f, scale));
                int w = Math.max(24, Math.round(24 * s));
                int h = Math.max(34, Math.round(34 * s));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.scale(s, s);

                Path arrow = new Path();
                arrow.moveTo(1f, 1f);
                arrow.lineTo(1f, 26f);
                arrow.lineTo(6.5f, 20.5f);
                arrow.lineTo(11f, 30f);
                arrow.lineTo(15.5f, 28f);
                arrow.lineTo(11f, 18.5f);
                arrow.lineTo(19f, 18.5f);
                arrow.close();

                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(Color.WHITE);
                fill.setStyle(Paint.Style.FILL);

                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setColor(Color.rgb(20, 25, 35));
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2.2f);
                stroke.setStrokeJoin(Paint.Join.ROUND);

                c.drawPath(arrow, fill);
                c.drawPath(arrow, stroke);
                icon = PointerIcon.create(bmp, 1f, 1f);
            }
            sDisplayThemedIcons.put(targetDisp, icon);
            sDisplayStyles.put(targetDisp, style);
            sDisplayScales.put(targetDisp, scale);
            if (curSvg != null) {
                sDisplaySvgs.put(targetDisp, curSvg);
            } else {
                sDisplaySvgs.remove(targetDisp);
            }
            return icon;
        } catch (Throwable t) {
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW);
        }
    }

    public static void clearThemedPointerCache() {
        sDisplayThemedIcons.clear();
        sDisplayStyles.clear();
        sDisplayScales.clear();
        sDisplaySvgs.clear();
        sCachedThemedIcon = null;
        sCachedStyle = -1;
        sCachedScale = -1f;
    }

    public EmptyPointerFrameLayout(Context context) {
        super(context);
        applyThemedPointer();
    }

    public EmptyPointerFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyThemedPointer();
    }

    public EmptyPointerFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyThemedPointer();
    }

    public void setTouchableInsetsProvider(TouchableInsetsProvider provider) {
        this.mInsetsProvider = provider;
        attachInsetsListenerIfAttached();
        requestLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachInsetsListenerIfAttached();
    }

    @Override
    protected void onDetachedFromWindow() {
        detachInsetsListener();
        super.onDetachedFromWindow();
    }

    private void attachInsetsListenerIfAttached() {
        if (mInsetsProvider == null || !isAttachedToWindow()) return;
        if (mInsetsListenerProxy != null) return;
        try {
            Class<?> vtoClass = ViewTreeObserver.class;
            Class<?> listenerClass = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoClass = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            final Field touchableRegionField = infoClass.getField("touchableRegion");
            final Method setTouchableInsetsMethod = infoClass.getMethod("setTouchableInsets", int.class);

            final int INSETS_FRAME = 0;
            final int INSETS_REGION = 3;

            mInsetsListenerProxy = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onComputeInternalInsets".equals(method.getName()) && args != null && args.length > 0) {
                            Object info = args[0];
                            if (mInsetsProvider != null && mInsetsProvider.isWholeWindowTouchable()) {
                                setTouchableInsetsMethod.invoke(info, INSETS_FRAME);
                            } else {
                                setTouchableInsetsMethod.invoke(info, INSETS_REGION);
                                Region region = (Region) touchableRegionField.get(info);
                                if (region != null) {
                                    java.util.List<Rect> list = (mInsetsProvider != null) ? mInsetsProvider.getTouchableRects() : null;
                                    if (list != null && !list.isEmpty()) {
                                        region.setEmpty();
                                        for (Rect r : list) {
                                            if (r != null && !r.isEmpty()) {
                                                region.op(r, Region.Op.UNION);
                                            }
                                        }
                                    } else {
                                        Rect r = (mInsetsProvider != null) ? mInsetsProvider.getTouchableRect() : null;
                                        if (r != null && !r.isEmpty()) {
                                            region.set(r);
                                        } else {
                                            int w = getWidth();
                                            int h = getHeight();
                                            int fallbackH = (mInsetsProvider != null) ? mInsetsProvider.getFallbackDockHeight() : 120;
                                            region.set(0, Math.max(0, h - fallbackH), Math.max(1, w), Math.max(1, h));
                                        }
                                    }
                                }
                            }
                        }
                        return null;
                    });

            Method addListenerMethod = vtoClass.getMethod("addOnComputeInternalInsetsListener", listenerClass);
            addListenerMethod.invoke(getViewTreeObserver(), mInsetsListenerProxy);
            Log.i(TAG, "OnComputeInternalInsetsListener 绑定成功");
        } catch (Throwable t) {
            Log.w(TAG, "OnComputeInternalInsetsListener 绑定失败: " + t.getMessage());
        }
    }

    private void detachInsetsListener() {
        if (mInsetsListenerProxy == null) return;
        try {
            Class<?> vtoClass = ViewTreeObserver.class;
            Class<?> listenerClass = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Method removeListenerMethod = vtoClass.getMethod("removeOnComputeInternalInsetsListener", listenerClass);
            removeListenerMethod.invoke(getViewTreeObserver(), mInsetsListenerProxy);
        } catch (Throwable ignored) {}
        mInsetsListenerProxy = null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mInsetsProvider != null && !mInsetsProvider.isWholeWindowTouchable()) {
            Rect r = mInsetsProvider.getTouchableRect();
            int x = (int) ev.getX();
            int y = (int) ev.getY();
            boolean inside = false;
            if (r != null && !r.isEmpty()) {
                inside = r.contains(x, y);
            } else {
                int fallbackH = mInsetsProvider.getFallbackDockHeight();
                inside = (y >= getHeight() - fallbackH);
            }
            if (!inside) {
                // 不在 Dock 交互区，直接放弃消费，让事件穿透到下层应用
                return false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (mInsetsProvider != null && !mInsetsProvider.isWholeWindowTouchable()) {
            Rect r = mInsetsProvider.getTouchableRect();
            int x = (int) ev.getX();
            int y = (int) ev.getY();
            boolean inside = false;
            if (r != null && !r.isEmpty()) {
                inside = r.contains(x, y);
            } else {
                int fallbackH = mInsetsProvider.getFallbackDockHeight();
                inside = (y >= getHeight() - fallbackH);
            }
            if (!inside) {
                return false;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private void applyThemedPointer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PointerIcon icon = getThemedPointerIcon(getContext());
            if (icon != null) {
                setPointerIcon(icon);
            }
        }
    }

    public static int getPointerIconType(PointerIcon icon) {
        if (icon == null) return -1;
        try {
            Field f = PointerIcon.class.getDeclaredField("mType");
            f.setAccessible(true);
            return f.getInt(icon);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 本容器的光标一律由 MouseCursorView 绘制；系统指针永远是空的。
            // 这里若返回「主题化箭头」，USB 鼠标就会多出一个系统箭头（第二个图标）。
            PointerIcon resolved = super.onResolvePointerIcon(event, pointerIndex);
            if (resolved != null) {
                int type = getPointerIconType(resolved);
                if (type == PointerIcon.TYPE_ARROW || type == PointerIcon.TYPE_DEFAULT || type == -1) {
                    return getEmptyPointerIcon(getContext());
                }
            }
            return getEmptyPointerIcon(getContext());
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }
}
