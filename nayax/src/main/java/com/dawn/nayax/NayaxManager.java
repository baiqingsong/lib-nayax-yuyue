package com.dawn.nayax;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;

import java.lang.ref.WeakReference;

/**
 * Nayax 刷卡器管理器（MDB-232 Modbus RTU 协议）
 *
 * <h3>使用流程</h3>
 * <pre>
 * NayaxManager mgr = NayaxManager.getInstance();
 * mgr.setCallback(callback);
 * mgr.connect(7);           // 1. 打开串口 → onConnectionChanged(true)
 * mgr.init();               // 2. 初始化   → onDeviceReady()
 * mgr.startPayment(2.50f);  // 3. 收款     → onPaymentStarted() → onPaymentSuccess()
 * // 收款成功后出货结果自动上报给设备，无需额外调用
 * </pre>
 *
 * <h3>内部机制</h3>
 * <ul>
 *   <li>心跳保活：每 5s 查询一次状态，防止设备因通信超时禁用刷卡器</li>
 *   <li>初始化自动重试：复位无响应时最多重试 3 次</li>
 *   <li>收款超时：{@value #PAYMENT_TIMEOUT_MS}ms 无结果自动取消</li>
 *   <li>收款成功后自动向设备上报出货成功，无需外部干预</li>
 * </ul>
 */
public class NayaxManager {

    private static final String TAG = "NayaxManager";

    // ==================== 错误码 ====================
    /** 串口打开失败 */
    public static final int ERROR_PORT_OPEN          = 1;
    /** 发送失败 */
    public static final int ERROR_SEND               = 2;
    /** 接收异常 */
    public static final int ERROR_RECEIVE            = 3;
    /** 收款超时 */
    public static final int ERROR_PAYMENT_TIMEOUT    = 4;
    /** 响应数据无效 */
    public static final int ERROR_INVALID_RESPONSE   = 5;
    /** 重连次数耗尽 */
    public static final int ERROR_RECONNECT_EXHAUSTED = 6;
    /** 设备未就绪 */
    public static final int ERROR_NOT_READY          = 7;
    /** 初始化超时 */
    public static final int ERROR_INIT_TIMEOUT       = 8;
    /** 操作响应超时 */
    public static final int ERROR_OPERATION_TIMEOUT  = 9;
    /** 刷卡器硬件异常 */
    public static final int ERROR_DEVICE_FAULT       = 10;

    // ==================== Handler 消息类型 ====================
    private static final int MSG_SEND_RESET          = 0x01;
    private static final int MSG_POLL_STATUS         = 0x02;
    private static final int MSG_PAYMENT_TIMEOUT     = 0x03;
    private static final int MSG_RECONNECT           = 0x04;
    private static final int MSG_HEARTBEAT           = 0x05;
    private static final int MSG_INIT_TIMEOUT        = 0x06;
    private static final int MSG_OPERATION_TIMEOUT   = 0x07;
    /** 串口打开后检查接收线程是否存活 */
    private static final int MSG_STREAM_CHECK        = 0x08;
    /** 收款前状态预查超时 */
    private static final int MSG_CHECK_STATE_TIMEOUT = 0x09;
    /** 发出货失败后延迟重新发起收款 */
    private static final int MSG_RETRY_PAYMENT       = 0x0A;

    // ==================== 时间常量（ms） ====================
    /** 串口打开后延迟发送复位指令的时间 */
    private static final long RESET_DELAY_MS          = 1000L;
    /** 心跳轮询间隔（必须 <10s，防设备超时禁用） */
    private static final long HEARTBEAT_INTERVAL_MS   = 5000L;
    /** 支付中状态轮询间隔 */
    private static final long PAYMENT_POLL_MS          = 2000L;
    /** 收款总超时 */
    private static final long PAYMENT_TIMEOUT_MS       = 120_000L;
    /** 初始化（复位）超时 */
    private static final long INIT_TIMEOUT_MS          = 20_000L;
    /** 操作响应超时（FC=06/FC=10 指令） */
    private static final long OPERATION_TIMEOUT_MS     = 8_000L;
    /** 基础重连延迟 */
    private static final long RECONNECT_BASE_DELAY_MS  = 3000L;
    /** 两条指令之间的最小间隔，防止通讯卡顿 */
    private static final long MIN_CMD_INTERVAL_MS      = 500L;

    // ==================== 配置常量 ====================
    private static final int BAUD_RATE               = 9600;
    private static final int MAX_RECONNECT_ATTEMPTS  = 5;
    private static final int MAX_OPERATION_RETRIES   = 3;
    /** 接收缓冲区最大长度（字节数的2倍，十六进制字符） */
    private static final int MAX_BUFFER_SIZE         = 256;

    // ==================== 状态机 ====================
    private enum State {
        /** 串口未连接 */
        DISCONNECTED,
        /** 串口已打开，等待手动调用 init() 发起初始化 */
        IDLE,
        /** 已发送复位指令，等待 echo 响应 */
        RESETTING,
        /** 已发送分辨率查询，等待响应 */
        QUERYING_RESOLUTION,
        /** 设备就绪，心跳轮询中 */
        HEARTBEAT,
        /** 已发送扣款请求，等待设备 ACK（FC=10 响应） */
        REQUESTING_DEDUCTION,
        /** L1/L2 模式：等待用户刷卡（yy=1） */
        WAITING_CARD,
        /** 轮询状态等待扣款确认（yy=2） */
        POLLING_DEDUCTION,
        /** 已发送出货结果，等待 echo */
        REPORTING_RESULT,
        /** 收款前发状态查询，等待设备返回 yy 值再决策 */
        CHECKING_STATE
    }

    // ==================== 单例 ====================
    private static volatile NayaxManager instance;

    // ==================== 核心字段 ====================
    private final Handler handler;
    private NayaxSerialHelper serialUtil;
    private NayaxCallback callback;

    // ==================== 状态字段 ====================
    private State currentState = State.DISCONNECTED;
    private volatile boolean paying          = false;
    private volatile boolean deviceReady     = false;
    private volatile boolean manualDisconnect = false;

    // ==================== 连接字段 ====================
    private int serialPort;
    private int reconnectCount;
    private int operationRetryCount;

    // ==================== 支付字段 ====================
    /** 本次收款金额（元） */
    private float   paymentAmount;
    /** 本次收款货道 */
    private int     paymentChannel;
    /** MDB 等级（1=L1, 2=L2, 3=L3，默认 L3） */
    private int     mdbLevel = 3;
    /** 当前分辨率（从设备查询获取，默认 0.01） */
    private float   resolution = NayaxCommand.RESOLUTION;
    /** 是否有待执行的收款请求（等待初始化完成后自动继续），paymentAmount/paymentChannel 已保存 */
    private boolean pendingPayment = false;
    /** 上一次发送指令的时间戳，用于保证指令间隔 >= MIN_CMD_INTERVAL_MS */
    private long    lastSendTimeMs = 0L;

    // ==================== 数据缓冲 ====================
    private final StringBuilder dataBuffer = new StringBuilder();
    /** 串口打开后是否已收到过任何数据，用于检测 LSerialUtil 接收线程是否存活 */
    private volatile boolean firstDataReceived = false;

    // ==================== 构造 ====================

    private NayaxManager() {
        handler = new SafeHandler(this);
    }

    public static NayaxManager getInstance() {
        if (instance == null) {
            synchronized (NayaxManager.class) {
                if (instance == null) {
                    instance = new NayaxManager();
                }
            }
        }
        return instance;
    }

    // ==================== 公开 API ====================

    /**
     * 设置事件回调
     */
    public void setCallback(NayaxCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置 MDB 等级（1=L1, 2=L2, 3=L3）
     * <p>L3（默认）：直接下发扣款金额。<br>
     * L1/L2：需等待用户刷卡后再下发扣款指令。</p>
     *
     * @param level MDB 等级，取值 1、2 或 3
     */
    public void setMdbLevel(int level) {
        if (level < 1 || level > 3) throw new IllegalArgumentException("MDB 等级须为 1、2 或 3");
        NayaxLog.i(TAG, "setMdbLevel: " + level);
        this.mdbLevel = level;
    }

    /**
     * 连接串口并初始化设备
     * <p>成功连接后自动发送复位指令，复位完成后通过 {@link NayaxCallback#onDeviceReady()} 通知。</p>
     *
     * @param port 串口编号（必须 &ge; 0）
     */
    public void connect(int port) {
        if (port < 0) {
            notifyError(ERROR_PORT_OPEN, "无效的串口号: " + port);
            return;
        }
        NayaxLog.i(TAG, "connect: port=" + port);
        this.serialPort      = port;
        this.reconnectCount  = 0;
        this.manualDisconnect = false;
        openSerialPort();
    }

    /**
     * 手动发起设备初始化（发送复位指令）
     *
     * <p>必须在 {@link #connect(int)} 且收到 {@link NayaxCallback#onConnectionChanged(boolean)}
     * 回调为 {@code true} 之后调用。初始化成功后触发 {@link NayaxCallback#onDeviceReady()}。</p>
     *
     * <p>将打开串口与发初始化指令分离，便于在确认串口可收发数据后再触发初始化。</p>
     */
    public void init() {
        if (!isConnected()) {
            notifyError(ERROR_NOT_READY, "串口未连接，请先调用 connect()");
            return;
        }
        NayaxLog.i(TAG, "手动发起初始化（可重复调用）");
        startInit();
    }

    /**
     * 断开串口连接
     */
    public void disconnect() {
        NayaxLog.i(TAG, "disconnect");
        manualDisconnect = true;
        cancelAllMessages();
        paying      = false;
        deviceReady = false;
        currentState = State.DISCONNECTED;
        dataBuffer.setLength(0);
        closeSerialPort();
    }

    /**
     * 释放所有资源，销毁单例
     */
    public void release() {
        NayaxLog.i(TAG, "release");
        disconnect();
        handler.removeCallbacksAndMessages(null);
        callback = null;
        synchronized (NayaxManager.class) {
            instance = null;
        }
    }

    /**
     * 发起收款（使用默认货道 1）
     *
     * @param amount 收款金额（元，&gt;0）
     */
    public void startPayment(float amount) {
        startPayment(amount, 1);
    }

    /**
     * 发起收款
     *
     * <p>内部逻辑：</p>
     * <ol>
     *   <li>若串口未连接，报错返回</li>
     *   <li>若设备未初始化，自动发起初始化；初始化完成后自动继续收款</li>
     *   <li>若设备已就绪，先查询设备状态：<br>
     *       - yy=0（待刷卡）：直接发收款指令<br>
     *       - yy 非 0（设备有挂起状态）：先发出货失败清除状态，等响应后再发收款</li>
     * </ol>
     *
     * @param amount  收款金额（元，&gt;0）
     * @param channel 货道编号（从 1 开始）
     */
    public void startPayment(float amount, int channel) {
        if (paying) {
            NayaxLog.w(TAG, "收款进行中，忽略重复请求");
            return;
        }
        if (!isConnected()) {
            notifyError(ERROR_NOT_READY, "串口未连接");
            return;
        }
        if (amount <= 0) {
            notifyError(ERROR_SEND, "收款金额必须大于 0");
            return;
        }
        if (channel <= 0) {
            notifyError(ERROR_SEND, "货道编号必须大于 0");
            return;
        }

        paymentAmount  = amount;
        paymentChannel = channel;

        if (!deviceReady) {
            // 设备未就绪：记录 pending，自动触发初始化；初始化完成后 onDeviceReady 会继续
            NayaxLog.i(TAG, "设备未初始化，自动发起初始化，收款请求将在就绪后执行");
            pendingPayment = true;
            startInit();
            return;
        }

        // 设备已就绪：先查询当前状态，由 onCheckingStateFrame 决策
        NayaxLog.i(TAG, "发起收款前查询设备状态: amount=" + amount + " channel=" + channel);
        paying = true;
        pendingPayment = false;
        cancelHeartbeat();
        currentState = State.CHECKING_STATE;
        sendCommand(NayaxCommand.queryStatus());
        handler.sendEmptyMessageDelayed(MSG_CHECK_STATE_TIMEOUT, OPERATION_TIMEOUT_MS);
    }

    /**
     * 取消当前收款
     * <p>协议无专用取消指令，发送出货失败通知设备当前交易作废，
     * 设备收到后会将刷卡器恢复到待刷卡状态。</p>
     */
    public void cancelPayment() {
        if (!paying && !pendingPayment) {
            NayaxLog.w(TAG, "当前无收款进行中");
            return;
        }
        NayaxLog.i(TAG, "取消收款，发送出货失败指令通知设备");
        cancelAllMessages();
        paying         = false;
        pendingPayment = false;
        currentState   = State.HEARTBEAT;
        sendCommand(NayaxCommand.vendingFail());
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onPaymentCancelled();
        scheduleHeartbeat();
    }

    /** 串口是否已连接 */
    public boolean isConnected() {
        return serialUtil != null && serialUtil.isConnected();
    }

    /** 设备是否已就绪 */
    public boolean isReady() {
        return deviceReady;
    }

    /** 是否正在收款 */
    public boolean isPaying() {
        return paying;
    }

    /**
     * 开启或关闭调试日志（默认关闭）
     *
     * @param enabled true 开启，false 关闭
     */
    public static void setLogEnabled(boolean enabled) {
        NayaxLog.setEnabled(enabled);
    }

    // ==================== 内部：自动上报出货成功 ====================

    private void reportVendingSuccess() {
        if (!isConnected()) return;
        currentState        = State.REPORTING_RESULT;
        operationRetryCount = 0;
        NayaxLog.i(TAG, "自动上报出货成功");
        sendCommand(NayaxCommand.vendingSuccess());
        scheduleOperationTimeout();
    }

    // ==================== 串口管理 ====================

    private void openSerialPort() {
        NayaxLog.i(TAG, "正在打开串口 " + serialPort + "，波特率 " + BAUD_RATE);
        closeSerialPort();
        dataBuffer.setLength(0);

        serialUtil = new NayaxSerialHelper(serialPort, BAUD_RATE,
                new NayaxSerialHelper.Listener() {
                    @Override
                    public void onOpenError(String portPath, Exception e) {
                        NayaxLog.e(TAG, "串口打开失败: " + portPath, e);
                        handler.post(() -> handlePortOpenFailed());
                    }

                    @Override
                    public void onReceiveError(Exception e) {
                        NayaxLog.e(TAG, "串口接收异常", e);
                        notifyError(ERROR_RECEIVE, "串口接收异常: " + e.getMessage());
                        handler.post(() -> {
                            if (!isConnected()) onRuntimeDisconnect();
                        });
                    }

                    @Override
                    public void onSendError(Exception e) {
                        NayaxLog.e(TAG, "串口发送异常", e);
                        notifyError(ERROR_SEND, "串口发送异常: " + e.getMessage());
                    }

                    @Override
                    public void onDataReceived(String data) {
                        if (!TextUtils.isEmpty(data)) {
                            firstDataReceived = true;
                            NayaxLog.d(TAG, "收到原始数据: " + data);
                            handler.post(() -> handleSerialData(data));
                        }
                    }
                });

        if (!serialUtil.isConnected()) {
            serialUtil = null;
            handlePortOpenFailed();
            return;
        }

        NayaxLog.i(TAG, "串口 " + serialPort + " 打开成功");
        firstDataReceived = false;
        reconnectCount = 0;
        notifyConnectionChanged(true);
        // 串口已就绪，等待调用 init() 手动发起初始化
        currentState = State.IDLE;
        // 检测接收线程是否存活（LSerialUtil 在 EOF 时不回调，需主动探测）
        handler.sendEmptyMessageDelayed(MSG_STREAM_CHECK, 1500L);
    }

    private void closeSerialPort() {
        boolean wasConnected = isConnected();
        if (serialUtil != null) {
            NayaxLog.i(TAG, "关闭串口 " + serialPort);
            try { serialUtil.disconnect(); } catch (Exception e) {
                NayaxLog.w(TAG, "关闭串口异常", e);
            }
            serialUtil = null;
        }
        if (wasConnected) notifyConnectionChanged(false);
    }

    private void handlePortOpenFailed() {
        notifyError(ERROR_PORT_OPEN, "串口打开失败，端口: " + serialPort);
        attemptReconnect();
    }

    private void attemptReconnect() {
        if (manualDisconnect) {
            NayaxLog.d(TAG, "手动断连，不自动重连");
            return;
        }
        reconnectCount++;
        if (reconnectCount > MAX_RECONNECT_ATTEMPTS) {
            notifyError(ERROR_RECONNECT_EXHAUSTED,
                    "重连失败，已达最大重试次数: " + MAX_RECONNECT_ATTEMPTS);
            return;
        }
        long delay = RECONNECT_BASE_DELAY_MS * reconnectCount;
        NayaxLog.i(TAG, "第 " + reconnectCount + " 次重连，延迟 " + delay + "ms");
        handler.sendEmptyMessageDelayed(MSG_RECONNECT, delay);
    }

    private void onRuntimeDisconnect() {
        NayaxLog.w(TAG, "运行中串口断开");
        cancelAllMessages();
        if (paying) {
            paying = false;
            notifyError(ERROR_RECEIVE, "收款过程中串口断开");
        }
        deviceReady  = false;
        currentState = State.DISCONNECTED;
        dataBuffer.setLength(0);
        closeSerialPort();
        reconnectCount = 0;
        attemptReconnect();
    }

    // ==================== 指令发送 ====================

    private void startInit() {
        NayaxLog.i(TAG, "发起初始化序列");
        deviceReady = false;
        currentState = State.RESETTING;
        operationRetryCount = 0;
        handler.removeMessages(MSG_SEND_RESET);
        handler.removeMessages(MSG_INIT_TIMEOUT);
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_SEND_RESET, RESET_DELAY_MS);
    }

    private void sendReset() {
        NayaxLog.i(TAG, "发送复位指令");
        sendCommand(NayaxCommand.reset());
    }

    private void sendDeductionRequest() {
        int amountUnits = Math.round(paymentAmount / resolution);
        String cmd = NayaxCommand.requestDeduction(paymentChannel, amountUnits);
        NayaxLog.i(TAG, "发送扣款请求: channel=" + paymentChannel
                + " amount=" + paymentAmount + "元 resolution=" + resolution
                + " amountUnits=" + amountUnits + " cmd=" + cmd);
        currentState        = State.REQUESTING_DEDUCTION;
        operationRetryCount = 0;
        sendCommand(cmd);
        scheduleOperationTimeout();
    }

    private void sendCommand(String hex) {
        if (TextUtils.isEmpty(hex)) {
            NayaxLog.w(TAG, "指令为空，跳过");
            return;
        }
        if (!isConnected()) {
            NayaxLog.w(TAG, "串口未连接，无法发送: " + hex);
            return;
        }
        long now   = System.currentTimeMillis();
        long delta = now - lastSendTimeMs;
        if (delta >= MIN_CMD_INTERVAL_MS) {
            doSendCommand(hex);
        } else {
            long delay = MIN_CMD_INTERVAL_MS - delta;
            NayaxLog.d(TAG, "指令间隔不足，延迟 " + delay + "ms 发送: " + hex);
            handler.postDelayed(() -> doSendCommand(hex), delay);
        }
    }

    private void doSendCommand(String hex) {
        if (!isConnected()) {
            NayaxLog.w(TAG, "延迟发送时串口已断开: " + hex);
            return;
        }
        NayaxLog.d(TAG, "发送: " + hex);
        lastSendTimeMs = System.currentTimeMillis();
        serialUtil.sendHex(hex);
    }

    // ==================== 心跳调度 ====================

    private void scheduleHeartbeat() {
        handler.removeMessages(MSG_HEARTBEAT);
        handler.sendEmptyMessageDelayed(MSG_HEARTBEAT, HEARTBEAT_INTERVAL_MS);
    }

    private void cancelHeartbeat() {
        handler.removeMessages(MSG_HEARTBEAT);
        handler.removeMessages(MSG_POLL_STATUS);
    }

    private void performHeartbeat() {
        if (paying || !isConnected() || !deviceReady) return;
        currentState = State.HEARTBEAT;
        sendCommand(NayaxCommand.queryStatus());
    }

    // ==================== 支付轮询调度 ====================

    private void schedulePaymentPoll() {
        handler.removeMessages(MSG_POLL_STATUS);
        handler.sendEmptyMessageDelayed(MSG_POLL_STATUS, PAYMENT_POLL_MS);
    }

    private void schedulePaymentTimeout() {
        handler.removeMessages(MSG_PAYMENT_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_PAYMENT_TIMEOUT, PAYMENT_TIMEOUT_MS);
    }

    private void scheduleOperationTimeout() {
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_OPERATION_TIMEOUT, OPERATION_TIMEOUT_MS);
    }

    // ==================== 数据缓冲处理 ====================

    private void handleSerialData(String data) {
        if (TextUtils.isEmpty(data)) return;
        dataBuffer.append(data.trim().toUpperCase());
        processBuffer();
    }

    /**
     * 从缓冲区提取并处理完整 Modbus RTU 帧
     * <p>Modbus RTU 帧最短 5 字节（10 个 HEX 字符），尝试从缓冲头部匹配。</p>
     */
    private void processBuffer() {
        // Modbus 最短帧 5 字节 = 10 HEX 字符
        while (dataBuffer.length() >= 10) {
            // 定位设备地址 0x01
            int startIdx = dataBuffer.indexOf("01");
            if (startIdx < 0) {
                dataBuffer.setLength(0);
                return;
            }
            if (startIdx > 0) {
                NayaxLog.d(TAG, "丢弃前导字符: " + dataBuffer.substring(0, startIdx));
                dataBuffer.delete(0, startIdx);
            }

            boolean found = false;
            // 从最小帧长尝试至最大帧长（CRC 验证）
            for (int len = 10; len <= Math.min(dataBuffer.length(), MAX_BUFFER_SIZE); len += 2) {
                String candidate = dataBuffer.substring(0, len);
                if (NayaxCommand.validateCRC(candidate)) {
                    dataBuffer.delete(0, len);
                    NayaxLog.d(TAG, "解析帧: " + candidate);
                    processValidFrame(candidate);
                    found = true;
                    break;
                }
            }

            if (!found) {
                if (dataBuffer.length() > MAX_BUFFER_SIZE) {
                    NayaxLog.w(TAG, "缓冲区溢出，清空: " + dataBuffer);
                    dataBuffer.setLength(0);
                }
                return;
            }
        }
    }

    // ==================== 帧处理（状态机分发） ====================

    private void processValidFrame(String frame) {
        NayaxLog.i(TAG, "处理帧: " + frame + " 状态: " + currentState);
        switch (currentState) {
            case RESETTING:
                onResetFrame(frame);
                break;
            case QUERYING_RESOLUTION:
                onResolutionFrame(frame);
                break;
            case HEARTBEAT:
                onHeartbeatFrame(frame);
                break;
            case CHECKING_STATE:
                onCheckingStateFrame(frame);
                break;
            case WAITING_CARD:
                onWaitingCardFrame(frame);
                break;
            case REQUESTING_DEDUCTION:
                onRequestingDeductionFrame(frame);
                break;
            case POLLING_DEDUCTION:
                onPollingDeductionFrame(frame);
                break;
            case REPORTING_RESULT:
                onReportingResultFrame(frame);
                break;
            default:
                NayaxLog.d(TAG, "状态 " + currentState + " 下收到未处理帧: " + frame);
                break;
        }
    }

    // ==================== 各状态帧处理 ====================

    /** RESETTING：等待复位 echo */
    private void onResetFrame(String frame) {
        if (NayaxCommand.isWriteSingleAck(frame)
                && frame.toUpperCase().startsWith("010600080001")) {
            handler.removeMessages(MSG_OPERATION_TIMEOUT);
            NayaxLog.i(TAG, "复位成功，开始查询分辨率");
            currentState = State.QUERYING_RESOLUTION;
            operationRetryCount = 0;
            sendCommand(NayaxCommand.queryResolution());
            scheduleOperationTimeout();
        }
    }

    /** QUERYING_RESOLUTION：解析分辨率响应 */
    private void onResolutionFrame(String frame) {
        if (!NayaxCommand.isStatusResponse(frame)) return;
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        resolution = NayaxCommand.parseResolutionValue(frame);
        NayaxLog.i(TAG, "分辨率查询完成: " + resolution);
        deviceReady  = true;
        currentState = State.HEARTBEAT;
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onDeviceReady();
        if (pendingPayment) {
            pendingPayment = false;
            NayaxLog.i(TAG, "初始化完成，继续执行挂起的收款: amount=" + paymentAmount);
            paying = true;
            cancelHeartbeat();
            currentState = State.CHECKING_STATE;
            sendCommand(NayaxCommand.queryStatus());
            handler.sendEmptyMessageDelayed(MSG_CHECK_STATE_TIMEOUT, OPERATION_TIMEOUT_MS);
        } else {
            scheduleHeartbeat();
        }
    }

    /**
     * CHECKING_STATE：收款前预查状态，根据 yy 值决策
     * <ul>
     *   <li>yy=0（IDLE 待刷卡）：设备干净，直接发收款指令</li>
     *   <li>yy 非 0（有挂起状态）：先发出货失败清除，延迟 1.5s 后重新尝试发起收款</li>
     * </ul>
     */
    private void onCheckingStateFrame(String frame) {
        if (!NayaxCommand.isStatusResponse(frame)) return;
        handler.removeMessages(MSG_CHECK_STATE_TIMEOUT);
        int yy = NayaxCommand.parseCardState(frame);
        NayaxLog.i(TAG, "收款前状态检查: yy=" + yy);
        if (yy == NayaxCommand.CardState.IDLE) {
            NayaxLog.i(TAG, "设备空闲，直接发起收款");
            doStartDeduction();
        } else {
            NayaxLog.w(TAG, "设备非空闲 (yy=" + yy + ")，发出货失败后重试收款");
            sendCommand(NayaxCommand.vendingFail());
            handler.sendEmptyMessageDelayed(MSG_RETRY_PAYMENT, 1500L);
        }
    }

    /**
     * 实际发起扣款（CHECKING_STATE 决策后调用，或 MSG_RETRY_PAYMENT 延迟后调用）
     */
    private void doStartDeduction() {
        if (mdbLevel >= 3) {
            sendDeductionRequest();
        } else {
            currentState = State.WAITING_CARD;
            schedulePaymentPoll();
            schedulePaymentTimeout();
        }
    }

    /** HEARTBEAT：解析状态响应，检测刷卡器异常 */
    private void onHeartbeatFrame(String frame) {
        if (!NayaxCommand.isStatusResponse(frame)) return;
        int xx = NayaxCommand.parseDeviceStatus(frame);
        int yy = NayaxCommand.parseCardState(frame);
        NayaxLog.d(TAG, "心跳状态: xx=" + Integer.toHexString(xx)
                + " yy=" + Integer.toHexString(yy));
        if (xx >= 0 && (xx & 0x04) != 0) {
            // bit2=1：刷卡器硬件异常
            notifyError(ERROR_DEVICE_FAULT, "刷卡器硬件异常 (xx=0x" + Integer.toHexString(xx) + ")");
        }
        scheduleHeartbeat();
    }

    /** WAITING_CARD（L1/L2）：检测到刷卡后发送扣款请求 */
    private void onWaitingCardFrame(String frame) {
        if (!NayaxCommand.isStatusResponse(frame)) return;
        int yy = NayaxCommand.parseCardState(frame);
        NayaxLog.d(TAG, "等待刷卡，yy=" + yy);
        if (yy == NayaxCommand.CardState.SWIPED) {
            NayaxLog.i(TAG, "检测到刷卡，发送扣款请求");
            handler.removeMessages(MSG_POLL_STATUS);
            sendDeductionRequest();
        } else {
            // 继续轮询
            schedulePaymentPoll();
        }
    }

    /** REQUESTING_DEDUCTION：等待 FC=10 ACK */
    private void onRequestingDeductionFrame(String frame) {
        if (NayaxCommand.isDeductionErrorResponse(frame)) {
            handler.removeMessages(MSG_OPERATION_TIMEOUT);
            NayaxLog.e(TAG, "扣款请求被设备拒绝: " + frame);
            paying       = false;
            currentState = State.HEARTBEAT;
            notifyError(ERROR_INVALID_RESPONSE, "扣款请求被设备拒绝");
            scheduleHeartbeat();
            return;
        }
        if (NayaxCommand.isDeductionSuccessResponse(frame)) {
            handler.removeMessages(MSG_OPERATION_TIMEOUT);
            NayaxLog.i(TAG, "扣款请求已接受，轮询确认中");
            currentState = State.POLLING_DEDUCTION;
            NayaxCallback cb = this.callback;
            if (cb != null) cb.onPaymentStarted();
            schedulePaymentPoll();
            schedulePaymentTimeout();
        }
    }

    /** POLLING_DEDUCTION：轮询等待 yy=2 */
    private void onPollingDeductionFrame(String frame) {
        if (!NayaxCommand.isStatusResponse(frame)) return;
        int yy = NayaxCommand.parseCardState(frame);
        NayaxLog.d(TAG, "轮询扣款状态，yy=" + yy);
        switch (yy) {
            case NayaxCommand.CardState.DEDUCTED:
                // 扣款成功：通知上层，同时自动向设备上报出货成功
                handler.removeMessages(MSG_POLL_STATUS);
                handler.removeMessages(MSG_PAYMENT_TIMEOUT);
                NayaxLog.i(TAG, "扣款成功，金额=" + paymentAmount + "，自动上报出货成功");
                paying = false;
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onPaymentSuccess(paymentAmount);
                reportVendingSuccess();
                break;
            case NayaxCommand.CardState.IDLE:
                // 刷卡器回到空闲（可能用户撤卡/拒绝）
                // 继续轮询，不立即判断失败（可能只是短暂状态）
                schedulePaymentPoll();
                break;
            default:
                schedulePaymentPoll();
                break;
        }
    }

    /** REPORTING_RESULT：等待出货成功 echo */
    private void onReportingResultFrame(String frame) {
        if (!NayaxCommand.isWriteSingleAck(frame)) return;
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        NayaxLog.i(TAG, "出货成功 echo 已收到，恢复心跳");
        currentState = State.HEARTBEAT;
        scheduleHeartbeat();
    }

    // ==================== 操作超时处理 ====================

    private void handleOperationTimeout() {
        operationRetryCount++;
        if (operationRetryCount > MAX_OPERATION_RETRIES) {
            NayaxLog.e(TAG, "操作超时，已达最大重试次数，状态: " + currentState);
            if (currentState == State.RESETTING) {
                // 初始化重试耗尽：串口保持打开，等待用户手动再调 init()
                notifyError(ERROR_INIT_TIMEOUT, "设备初始化失败，已重试 " + MAX_OPERATION_RETRIES + " 次，请再次点击初始化");
                currentState = State.IDLE;
                return;
            }
            if (currentState == State.QUERYING_RESOLUTION) {
                // 分辨率查询失败：使用默认值继续流程
                NayaxLog.w(TAG, "分辨率查询超时，使用默认分辨率: " + NayaxCommand.RESOLUTION);
                resolution   = NayaxCommand.RESOLUTION;
                deviceReady  = true;
                currentState = State.HEARTBEAT;
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onDeviceReady();
                scheduleHeartbeat();
                return;
            }
            notifyError(ERROR_OPERATION_TIMEOUT, "操作响应超时，状态: " + currentState);
            if (paying) {
                paying = false;
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onPaymentCancelled();
            }
            currentState = State.HEARTBEAT;
            scheduleHeartbeat();
            return;
        }
        NayaxLog.w(TAG, "操作超时，第 " + operationRetryCount + " 次重试，状态: " + currentState);
        switch (currentState) {
            case REQUESTING_DEDUCTION:
                int units = Math.round(paymentAmount / resolution);
                sendCommand(NayaxCommand.requestDeduction(paymentChannel, units));
                scheduleOperationTimeout();
                break;
            case QUERYING_RESOLUTION:
                sendCommand(NayaxCommand.queryResolution());
                scheduleOperationTimeout();
                break;
            case REPORTING_RESULT:
                // 始终重试上报出货成功（已无失败路径）
                sendCommand(NayaxCommand.vendingSuccess());
                scheduleOperationTimeout();
                break;
            case RESETTING:
                sendReset();
                scheduleOperationTimeout();
                break;
            default:
                currentState = State.HEARTBEAT;
                scheduleHeartbeat();
                break;
        }
    }

    // ==================== 工具方法 ====================

    private void cancelAllMessages() {
        handler.removeMessages(MSG_SEND_RESET);
        handler.removeMessages(MSG_POLL_STATUS);
        handler.removeMessages(MSG_PAYMENT_TIMEOUT);
        handler.removeMessages(MSG_RECONNECT);
        handler.removeMessages(MSG_HEARTBEAT);
        handler.removeMessages(MSG_INIT_TIMEOUT);
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        handler.removeMessages(MSG_STREAM_CHECK);
        handler.removeMessages(MSG_CHECK_STATE_TIMEOUT);
        handler.removeMessages(MSG_RETRY_PAYMENT);
    }

    private void notifyError(int code, String message) {
        NayaxLog.e(TAG, "错误[" + code + "]: " + message);
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onError(code, message);
    }

    private void notifyConnectionChanged(boolean connected) {
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onConnectionChanged(connected);
    }

    // ==================== Handler 消息处理 ====================

    private void handleMsg(Message msg) {
        switch (msg.what) {

            case MSG_SEND_RESET:
                sendReset();
                scheduleOperationTimeout();  // 无响应时8s内自动重试
                break;

            case MSG_POLL_STATUS:
                if (paying) {
                    // 支付中：发送状态查询
                    sendCommand(NayaxCommand.queryStatus());
                    schedulePaymentPoll();
                }
                break;

            case MSG_PAYMENT_TIMEOUT:
                NayaxLog.w(TAG, "收款超时，发送出货失败指令通知设备");
                handler.removeMessages(MSG_POLL_STATUS);
                paying       = false;
                currentState = State.HEARTBEAT;
                sendCommand(NayaxCommand.vendingFail());
                notifyError(ERROR_PAYMENT_TIMEOUT, "收款超时");
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onPaymentCancelled();
                scheduleHeartbeat();
                break;

            case MSG_CHECK_STATE_TIMEOUT:
                NayaxLog.w(TAG, "收款前状态查询超时，直接尝试发起收款");
                if (paying) doStartDeduction();
                break;

            case MSG_RETRY_PAYMENT:
                NayaxLog.i(TAG, "重新发起收款（出货失败指令已发）");
                if (paying) {
                    currentState = State.CHECKING_STATE;
                    sendCommand(NayaxCommand.queryStatus());
                    handler.sendEmptyMessageDelayed(MSG_CHECK_STATE_TIMEOUT, OPERATION_TIMEOUT_MS);
                }
                break;

            case MSG_RECONNECT:
                openSerialPort();
                // 重连后自动发起初始化，无需上层手动调用 init()
                if (isConnected()) startInit();
                break;

            case MSG_HEARTBEAT:
                performHeartbeat();
                break;

            case MSG_STREAM_CHECK:
                if (!firstDataReceived && serialUtil != null && !deviceReady) {
                    NayaxLog.w(TAG, "串口打开 1.5s 内未收到任何数据，请确认 Nayax 设备已上电并连接至 ttyS" + serialPort);
                }
                break;

            case MSG_INIT_TIMEOUT:
                if (!deviceReady) {
                    NayaxLog.w(TAG, "设备初始化超时，串口保持打开，可重新点击初始化");
                    notifyError(ERROR_INIT_TIMEOUT, "设备初始化超时");
                    currentState = State.IDLE;
                    // 不自动关闭串口，不自动重连，等待用户手动重新调用 init()
                }
                break;

            case MSG_OPERATION_TIMEOUT:
                handleOperationTimeout();
                break;
        }
    }

    /**
     * 静态 Handler，避免内存泄漏
     */
    private static class SafeHandler extends Handler {
        private final WeakReference<NayaxManager> ref;

        SafeHandler(NayaxManager manager) {
            super(Looper.getMainLooper());
            ref = new WeakReference<>(manager);
        }

        @Override
        public void handleMessage(Message msg) {
            NayaxManager manager = ref.get();
            if (manager != null) manager.handleMsg(msg);
        }
    }
}
