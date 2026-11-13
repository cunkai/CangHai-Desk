package com.aosen.shizukudex;

import android.os.IBinder;
import android.view.InputDevice;
import android.view.MotionEvent;

/** 探测 root app_process 的 InputManager.injectInputEvent 是否真的可用。 */
public class RootBridgeProbe {
    private static Object im;
    private static java.lang.reflect.Method inject;
    private static java.lang.reflect.Method setDisplayId;
    private static java.lang.reflect.Method setActionButton;

    public static void main(String[] args) throws Exception {
        System.out.println("uid=" + android.os.Process.myUid());
        Class<?> sm = Class.forName("android.os.ServiceManager");
        java.lang.reflect.Method getService = sm.getDeclaredMethod("getService", String.class);
        IBinder inputBinder = (IBinder) getService.invoke(null, "input");
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        im = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, inputBinder);
        for (java.lang.reflect.Method m : im.getClass().getDeclaredMethods()) {
            if (m.getName().equals("injectInputEvent")) inject = m;
        }
        setDisplayId = Class.forName("android.view.InputEvent").getDeclaredMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getDeclaredMethod("setActionButton", int.class);
        System.out.println("inject=" + inject + " setDisplayId=" + setDisplayId + " setActionButton=" + setActionButton);

        test("HOVER_MOVE", MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0, 0);
        test("SCROLL_V", MotionEvent.ACTION_SCROLL, 0, 0, 0, 1.0f);
        test("BUTTON_PRESS_SECONDARY", MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 0, 0);
        test("DOWN_PRIMARY", MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 0, 0);
        System.out.println("PROBE_DONE");
    }

    private static void test(String label, int action, int buttonState, int actionButton,
                             float hscroll, float vscroll) throws Exception {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = 200f;
        coords.y = 500f;
        coords.pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
        coords.size = 1f;
        if (vscroll != 0f) coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        if (hscroll != 0f) coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        MotionEvent ev = MotionEvent.obtain(now, now, action, 1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) setActionButton.invoke(ev, actionButton);
        setDisplayId.invoke(ev, 0);
        Object result = inject.invoke(im, ev, 0);
        System.out.println(label + " -> inject=" + result
                + " displayId=" + readDisplayId(ev)
                + " buttonState=" + ev.getButtonState()
                + " actionButton=" + ev.getActionButton());
        ev.recycle();
    }

    private static Object readDisplayId(MotionEvent ev) throws Exception {
        try {
            java.lang.reflect.Method m = Class.forName("android.view.InputEvent")
                    .getDeclaredMethod("getDisplayId");
            return m.invoke(ev);
        } catch (Throwable t) {
            return "n/a";
        }
    }
}
