package com.dawn.nayax;

import android.util.Log;

/**
 * Nayax 日志工具类
 *
 * <p>默认关闭日志输出，调试时通过 {@link #setEnabled(boolean)} 开启。</p>
 *
 * <pre>
 * NayaxLog.setEnabled(true);   // 调试时开启
 * NayaxLog.setEnabled(false);  // 发版前关闭（默认）
 * </pre>
 */
public final class NayaxLog {

    private static volatile boolean sEnabled = false;

    private NayaxLog() {}

    /** 开启或关闭日志 */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** 是否已开启日志 */
    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void d(String tag, String msg) {
        if (sEnabled) Log.d(tag, msg);
    }

    public static void i(String tag, String msg) {
        if (sEnabled) Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        if (sEnabled) Log.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (sEnabled) Log.w(tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        if (sEnabled) Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (sEnabled) Log.e(tag, msg, tr);
    }
}
