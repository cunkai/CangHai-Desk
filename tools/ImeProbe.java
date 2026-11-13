package com.aosen.probe;

import java.lang.reflect.Method;

/** 一次性探针：列出本机 IWindowManager / WindowManager 与 IME 策略相关的方法。 */
public final class ImeProbe {
    public static void main(String[] args) {
        String[] classes = {
                "android.view.IWindowManager",
                "android.view.WindowManager",
                "android.hardware.display.VirtualDisplayConfig",
                "android.hardware.display.VirtualDisplayConfig$Builder",
        };
        for (String name : classes) {
            System.out.println("=== " + name + " ===");
            try {
                Class<?> c = Class.forName(name);
                for (Method m : c.getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("ime") || n.contains("policy") || n.contains("decor") ||
                            n.contains("trusted")) {
                        StringBuilder sb = new StringBuilder("  ");
                        sb.append(m.getReturnType().getSimpleName()).append(' ')
                          .append(m.getName()).append('(');
                        Class<?>[] ps = m.getParameterTypes();
                        for (int i = 0; i < ps.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(ps[i].getSimpleName());
                        }
                        sb.append(')');
                        System.out.println(sb);
                    }
                }
            } catch (Throwable t) {
                System.out.println("  <" + t + ">");
            }
        }
        System.out.println("=== done ===");
    }
}
