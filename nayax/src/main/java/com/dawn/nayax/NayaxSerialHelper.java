package com.dawn.nayax;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android_serialport_api.SafeSerialPort;

/**
 * Nayax 串口封装
 *
 * <p>替代 LSerialUtil，解决非阻塞串口设备上 read() 立即返回 -1
 * 导致接收线程退出的问题：遇到 -1 时短暂等待后继续轮询，而非退出。</p>
 */
class NayaxSerialHelper {

    interface Listener {
        void onOpenError(String portPath, Exception e);
        void onReceiveError(Exception e);
        void onSendError(Exception e);
        /** 在主线程回调，data 为大写连续 HEX 字符串，如 "010600080001C9C8" */
        void onDataReceived(String data);
    }

    private static final String TAG        = "NayaxSerial";
    private static final String PATH_TTY   = "/dev/ttyS";
    /** read() 返回 -1（非阻塞无数据）时的轮询间隔 */
    private static final int    POLL_MS    = 10;
    private static final int    BUF_SIZE   = 1024;

    private final String  mPortPath;
    private final Listener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private SafeSerialPort mPort;
    private InputStream    mIn;
    private OutputStream   mOut;
    private Thread         mRecvThread;
    private volatile boolean mConnected = false;

    NayaxSerialHelper(int port, int baudRate, Listener listener) {
        mPortPath = PATH_TTY + port;
        mListener = listener;
        open(baudRate);
    }

    // ==================== 打开 ====================

    private void open(int baudRate) {
        try {
            mPort = new SafeSerialPort(new File(mPortPath), baudRate, 8, 1, 'N');
            mIn   = mPort.getInputStream();
            mOut  = mPort.getOutputStream();
            mConnected = true;
            startReceiver();
            Log.i(TAG, "串口打开成功: " + mPortPath + " 波特率=" + baudRate);
        } catch (Exception e) {
            Log.e(TAG, "串口打开失败: " + mPortPath, e);
            mConnected = false;
            if (mListener != null) mListener.onOpenError(mPortPath, e);
        }
    }

    // ==================== 接收线程 ====================

    private void startReceiver() {
        mRecvThread = new Thread(() -> {
            byte[] buf = new byte[BUF_SIZE];
            while (!Thread.currentThread().isInterrupted() && mConnected) {
                try {
                    int n = mIn.read(buf);
                    if (n > 0) {
                        final String hex = bytesToHex(buf, n);
                        Log.d(TAG, "收到原始数据: " + hex);
                        mMainHandler.post(() -> {
                            if (mListener != null) mListener.onDataReceived(hex);
                        });
                    } else if (n == -1) {
                        // 非阻塞串口：无数据时 read() 返回 -1，等待后继续轮询，不退出
                        try {
                            Thread.sleep(POLL_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // n == 0：同样继续循环
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted() && mConnected) {
                        Log.e(TAG, "接收异常: " + e.getMessage(), e);
                        mMainHandler.post(() -> {
                            if (mListener != null) mListener.onReceiveError(e);
                        });
                    }
                    break;
                }
            }
            Log.d(TAG, "接收线程退出: " + mPortPath);
        }, "NayaxHexReceiver");
        mRecvThread.setDaemon(true);
        mRecvThread.start();
    }

    // ==================== 发送 ====================

    void sendHex(String hex) {
        if (!mConnected) {
            Log.w(TAG, "串口未连接，无法发送: " + hex);
            return;
        }
        new Thread(() -> {
            try {
                byte[] data = hexToBytes(hex);
                synchronized (NayaxSerialHelper.this) {
                    if (mOut != null) {
                        mOut.write(data);
                        mOut.flush();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "发送异常: " + e.getMessage(), e);
                mMainHandler.post(() -> {
                    if (mListener != null) mListener.onSendError(e);
                });
            }
        }, "NayaxSend").start();
    }

    // ==================== 断开 ====================

    synchronized void disconnect() {
        mConnected = false;
        if (mRecvThread != null) {
            mRecvThread.interrupt();
            mRecvThread = null;
        }
        try { if (mIn  != null) { mIn.close();  mIn  = null; } } catch (Exception ignored) {}
        try { if (mOut != null) { mOut.close(); mOut = null; } } catch (Exception ignored) {}
        try { if (mPort != null) { mPort.close(); mPort = null; } } catch (Exception ignored) {}
        Log.i(TAG, "串口已关闭: " + mPortPath);
    }

    // ==================== 状态 ====================

    boolean isConnected() {
        return mConnected;
    }

    // ==================== 工具 ====================

    static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
