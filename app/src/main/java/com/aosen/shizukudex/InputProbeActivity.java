package com.aosen.shizukudex;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 输入探针：跑在串流虚拟屏上的原生 Activity。
 * 只做一件事——把系统真正投递进来的输入事件原样显示 + 打 logcat，
 * 用来回答「点击到底有没有到应用窗口、到的是 DOWN / UP 还是 CANCEL」。
 */
public class InputProbeActivity extends Activity {
    private static final String TAG = "CanghaiProbe";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<String> lines = new ArrayList<>();
    private TextView out;
    private int downs, ups, cancels, moves, generics, hovers, keys;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#10161c"));
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("沧海 · 输入探针");
        title.setTextColor(Color.parseColor("#7ee0ff"));
        title.setTextSize(26f);
        root.addView(title);

        out = new TextView(this);
        out.setTextColor(Color.parseColor("#e6f1f7"));
        out.setTextSize(15f);
        out.setTypeface(Typeface.MONOSPACE);
        out.setPadding(0, 18, 0, 0);

        ScrollView sv = new ScrollView(this);
        sv.addView(out, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int dpi = getResources().getConfiguration().densityDpi;
        emit("READY displayId=" + getDisplay().getDisplayId()
                + "  " + w + "x" + h + "@" + dpi + "dpi");
        emit("等待输入：用鼠标左键点本窗口任意位置，或由 adb 注入一次点击");
    }

    @Override
    protected void onResume() {
        super.onResume();
        emit("RESUME hasFocus=" + hasWindowFocus());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        emit("FOCUS " + hasFocus);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        count(ev);
        emit("TOUCH   " + describe(ev));
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        generics++;
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_HOVER_MOVE) {
            hovers++;
            if (hovers % 20 == 1) {
                emit("GENERIC HOVER_MOVE x" + hovers + "  " + xy(ev));
            }
        } else {
            count(ev);
            emit("GENERIC " + describe(ev));
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        keys++;
        emit("KEY     " + (ev.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " code=" + ev.getKeyCode()
                + " src=0x" + Integer.toHexString(ev.getSource())
                + " display=" + eventDisplayId(ev)
                + " dev=" + ev.getDeviceId());
        return super.dispatchKeyEvent(ev);
    }

    private void count(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downs++; break;
            case MotionEvent.ACTION_UP: ups++; break;
            case MotionEvent.ACTION_CANCEL: cancels++; break;
            case MotionEvent.ACTION_MOVE: moves++; break;
            default: break;
        }
    }

    private static String xy(MotionEvent ev) {
        return "x=" + String.format(Locale.US, "%.1f", ev.getX())
                + " y=" + String.format(Locale.US, "%.1f", ev.getY());
    }

    private static String describe(MotionEvent ev) {
        return actionName(ev.getActionMasked())
                + " src=0x" + Integer.toHexString(ev.getSource())
                + "(" + sourceName(ev.getSource()) + ")"
                + " " + xy(ev)
                + " raw=" + String.format(Locale.US, "%.1f,%.1f", ev.getRawX(), ev.getRawY())
                + " display=" + eventDisplayId(ev)
                + " dev=" + ev.getDeviceId()
                + " btn=0x" + Integer.toHexString(ev.getButtonState())
                + " actionBtn=" + ev.getActionButton()
                + " ptrs=" + ev.getPointerCount()
                + " flags=0x" + Integer.toHexString(ev.getFlags())
                + " obscured=" + ((ev.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0)
                + " partial=" + ((ev.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0);
    }

    /** InputEvent.getDisplayId() 是 hidden API，反射取，取不到就退回 Activity 所在的屏。 */
    private static int eventDisplayId(android.view.InputEvent ev) {
        try {
            return (Integer) ev.getClass().getMethod("getDisplayId").invoke(ev);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String actionName(int a) {
        switch (a) {
            case MotionEvent.ACTION_DOWN: return "DOWN         ";
            case MotionEvent.ACTION_UP: return "UP           ";
            case MotionEvent.ACTION_MOVE: return "MOVE         ";
            case MotionEvent.ACTION_CANCEL: return "*** CANCEL **";
            case MotionEvent.ACTION_OUTSIDE: return "OUTSIDE      ";
            case MotionEvent.ACTION_POINTER_DOWN: return "POINTER_DOWN ";
            case MotionEvent.ACTION_POINTER_UP: return "POINTER_UP   ";
            case MotionEvent.ACTION_HOVER_MOVE: return "HOVER_MOVE   ";
            case MotionEvent.ACTION_HOVER_ENTER: return "HOVER_ENTER  ";
            case MotionEvent.ACTION_HOVER_EXIT: return "HOVER_EXIT   ";
            case MotionEvent.ACTION_SCROLL: return "SCROLL       ";
            case MotionEvent.ACTION_BUTTON_PRESS: return "BTN_PRESS    ";
            case MotionEvent.ACTION_BUTTON_RELEASE: return "BTN_RELEASE  ";
            default: return "ACTION_" + a + "     ";
        }
    }

    private static String sourceName(int s) {
        switch (s) {
            case 0x1002: return "TOUCHSCREEN";
            case 0x2002: return "MOUSE";
            case 0x20004: return "MOUSE_RELATIVE";
            case 0x1008: return "TOUCHPAD";
            case 0x4002: return "STYLUS";
            case 0x101: return "KEYBOARD";
            case 0x201: return "DPAD";
            default: return "?";
        }
    }

    private void emit(final String line) {
        Log.i(TAG, line);
        main.post(() -> {
            lines.add(line);
            while (lines.size() > 45) lines.remove(0);
            StringBuilder sb = new StringBuilder();
            sb.append("DOWN=").append(downs)
              .append("  UP=").append(ups)
              .append("  CANCEL=").append(cancels)
              .append("  MOVE=").append(moves)
              .append("  GENERIC=").append(generics)
              .append("  HOVER=").append(hovers)
              .append("  KEY=").append(keys)
              .append('\n');
            sb.append("—— 若 DOWN/UP 始终为 0、只有 CANCEL，说明事件在 InputDispatcher 就被丢了 ——\n\n");
            for (String l : lines) sb.append(l).append('\n');
            out.setText(sb.toString());
        });
    }
}
