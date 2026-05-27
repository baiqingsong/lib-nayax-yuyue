package com.dawn.nayax;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;

import com.dawn.serial.LSerialUtil;

import java.lang.ref.WeakReference;

/**
 * Nayax 刷卡器管理器（MDB-232 Modbus RTU 协议）
 *
 * <h3>协议说明</h3>
 * <p>通过 MDB-232 转换盒与 Nayax 刷卡器通信，使用 Modbus RTU 协议。
 * 若通讯间隔超过 10s，转换盒将自动禁止刷卡器功能，故内部保持定时心跳轮询。</p>
 *
 * <h3>MDB 等级</h3>
 * <ul>
 *   <li><b>L3（默认）</b>：直接下发扣款金额，无需等待用户先刷卡。</li>
 *   <li><b>L1/L2</b>：需等待用户刷卡（{@link NayaxCallback#onCardSwiped}）后再下发扣款指令。</li>
 * </ul>
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>{@link #setCallback(NayaxCallback)} 设置回调</li>
 *   <li>{@link #connect(int)} 连接串口</li>
 *   <li>等待 {@link NayaxCallback#onDeviceReady()} 回调</li>
 *   <li>{@link #startPayment(float)} 发起收款（默认货道 1）</li>
 *   <li>等待 {@link NayaxCallback#onPaymentSuccess(float)} 回调</li>
 *   <li>{@link #reportVendingResult(boolean)} 上报出货结果</li>
 *   <li>等待 {@link NayaxCallback#onVendingAck(boolean)} 回调</li>
 * </ol>
 *
 * <h3>稳定性特性</h3>
 * <ul>
 *   <li>心跳保活：每 5s 查询一次状态，防止转换盒因通信超时禁用外设</li>
 *   <li>初始化超时：复位后未及时响应则自动重连</li>
 *   <li>支付超时：可配置，超时后自动通知错误并恢复心跳</li>
 *   <li>操作重试：关键指令支持自动重试</li>
 *   <li>自动重连：断连后指数退避重连</li>
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
        /** 已发送复位指令，等待 echo 响应 */
        RESETTING,
        /** 设备就绪，心跳轮询中 */
        HEARTBEAT,
        /** 已发送扣款请求，等待设备 ACK（FC=10 响应） */
        REQUESTING_DEDUCTION,
        /** L1/L2 模式：等待用户刷卡（yy=1） */
        WAITING_CARD,
        /** 轮询状态等待扣款确认（yy=2） */
        POLLING_DEDUCTION,
        /** 已发送出货结果，等待 echo */
        REPORTING_RESULT
    }

    // ==================== 单例 ====================
    private static volatile NayaxManager instance;

    // ==================== 核心字段 ====================
    private final Handler handler;
    private LSerialUtil serialUtil;
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
    /** 最后一次上报的出货结果（true=成功），用于超时重试 */
    private boolean lastVendingSuccess = true;

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
        if (!deviceReady) {
            notifyError(ERROR_NOT_READY, "设备未就绪，请等待初始化完成");
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

        paying         = true;
        paymentAmount  = amount;
        paymentChannel = channel;
        cancelHeartbeat();

        NayaxLog.i(TAG, "发起收款: amount=" + amount + " channel=" + channel
                + " mdbLevel=" + mdbLevel);

        if (mdbLevel >= 3) {
            // L3：直接发送扣款指令
            sendDeductionRequest();
        } else {
            // L1/L2：等待用户刷卡
            currentState = State.WAITING_CARD;
            schedulePaymentPoll();
            schedulePaymentTimeout();
        }
    }

    /**
     * 取消收款
     */
    public void cancelPayment() {
        if (!paying) {
            NayaxLog.w(TAG, "当前无收款进行中");
            return;
        }
        NayaxLog.i(TAG, "取消收款");
        cancelAllMessages();
        paying       = false;
        currentState = State.HEARTBEAT;
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onPaymentCancelled();
        scheduleHeartbeat();
    }

    /**
     * 上报出货结果
     *
     * @param success true 表示出货成功，false 表示出货失败
     */
    public void reportVendingResult(boolean success) {
        if (!isConnected()) {
            notifyError(ERROR_NOT_READY, "串口未连接，无法上报出货结果");
            return;
        }
        currentState         = State.REPORTING_RESULT;
        operationRetryCount  = 0;
        lastVendingSuccess   = success;
        NayaxLog.i(TAG, "上报出货结果: " + (success ? "成功" : "失败"));
        sendCommand(success ? NayaxCommand.vendingSuccess() : NayaxCommand.vendingFail());
        scheduleOperationTimeout();
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

    /** 当前串口号 */
    public int getCurrentPort() {
        return serialPort;
    }

    /** 当前 MDB 等级 */
    public int getMdbLevel() {
        return mdbLevel;
    }

    /**
     * 开启或关闭调试日志
     *
     * <p>等同于 {@link NayaxLog#setEnabled(boolean)}，默认关闭。</p>
     *
     * @param enabled true 开启日志，false 关闭日志
     */
    public static void setLogEnabled(boolean enabled) {
        NayaxLog.setEnabled(enabled);
    }

    /** 调试日志是否已开启 */
    public static boolean isLogEnabled() {
        return NayaxLog.isEnabled();
    }

    // ==================== 串口管理 ====================

    private void openSerialPort() {
        NayaxLog.i(TAG, "正在打开串口 " + serialPort + "，波特率 " + BAUD_RATE);
        closeSerialPort();
        dataBuffer.setLength(0);

        serialUtil = new LSerialUtil(serialPort, BAUD_RATE, LSerialUtil.SerialType.TYPE_HEX,
                new LSerialUtil.OnSerialListener() {
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
        // 延迟后发送复位指令
        currentState = State.RESETTING;
        handler.sendEmptyMessageDelayed(MSG_SEND_RESET, RESET_DELAY_MS);
        handler.sendEmptyMessageDelayed(MSG_INIT_TIMEOUT, INIT_TIMEOUT_MS);
        // 检测接收线程是否存活（LSerialUtil 在 EOF 时不回调，需主动探测）
        handler.sendEmptyMessageDelayed(MSG_STREAM_CHECK, 1500L);
    }

    private void closeSerialPort() {
        boolean wasConnected = isConnected();
        if (serialUtil != null) {
            NayaxLog.i(TAG, "关闭串口 " + serialPort
                    + (firstDataReceived ? "" : "（接收线程已死，跳过 disconnect 避免 JNI crash）"));
            if (firstDataReceived || !streamThreadDead()) {
                try { serialUtil.disconnect(); } catch (Exception e) {
                    NayaxLog.w(TAG, "关闭串口异常", e);
                }
            }
            serialUtil = null;
        }
        if (wasConnected) notifyConnectionChanged(false);
    }

    /**
     * 判断 LSerialUtil 的接收线程是否已死亡（即从未收到任何数据）。
     * <p>LSerialUtil 在接收流 EOF 时不回调 onReceiveError，接收线程静默退出。
     * 此时调用 {@code LSerialUtil.disconnect()} 会触发 SafeSerialPort.close() JNI crash（
     * NoSuchFieldError: mFd），因此必须跳过。</p>
     */
    private boolean streamThreadDead() {
        // 若串口打开后从未收到任何数据，且当前仍处于初始化/复位状态，
        // 判断接收线程已死（LSerialUtil HEX输入流已结束）
        return !firstDataReceived
                && (currentState == State.RESETTING || currentState == State.DISCONNECTED);
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

    private void sendReset() {
        NayaxLog.i(TAG, "发送复位指令");
        sendCommand(NayaxCommand.reset());
    }

    private void sendDeductionRequest() {
        int amountCents = Math.round(paymentAmount / NayaxCommand.RESOLUTION);
        String cmd = NayaxCommand.requestDeduction(paymentChannel, amountCents);
        NayaxLog.i(TAG, "发送扣款请求: channel=" + paymentChannel
                + " amountCents=" + amountCents + " cmd=" + cmd);
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
        NayaxLog.d(TAG, "发送: " + hex);
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
            case HEARTBEAT:
                onHeartbeatFrame(frame);
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
        // 复位 ACK = echo of reset command
        if (NayaxCommand.isWriteSingleAck(frame)
                && frame.toUpperCase().startsWith("010600080001")) {
            handler.removeMessages(MSG_INIT_TIMEOUT);
            NayaxLog.i(TAG, "复位成功，设备就绪");
            deviceReady  = true;
            currentState = State.HEARTBEAT;
            NayaxCallback cb = this.callback;
            if (cb != null) cb.onDeviceReady();
            scheduleHeartbeat();
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
            NayaxCallback cb = this.callback;
            if (cb != null) cb.onCardSwiped();
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
                // 扣款成功
                handler.removeMessages(MSG_POLL_STATUS);
                handler.removeMessages(MSG_PAYMENT_TIMEOUT);
                NayaxLog.i(TAG, "扣款成功，金额=" + paymentAmount);
                currentState = State.HEARTBEAT;
                paying       = false;
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onPaymentSuccess(paymentAmount);
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

    /** REPORTING_RESULT：等待出货结果 echo */
    private void onReportingResultFrame(String frame) {
        // 出货成功/失败均为 FC=06 echo
        if (!NayaxCommand.isWriteSingleAck(frame)) return;
        // 检查是出货成功还是失败的 echo
        boolean success = frame.toUpperCase().startsWith("010600050001");
        handler.removeMessages(MSG_OPERATION_TIMEOUT);
        NayaxLog.i(TAG, "出货结果已确认: " + (success ? "成功" : "失败"));
        currentState = State.HEARTBEAT;
        NayaxCallback cb = this.callback;
        if (cb != null) cb.onVendingAck(success);
        scheduleHeartbeat();
    }

    // ==================== 操作超时处理 ====================

    private void handleOperationTimeout() {
        operationRetryCount++;
        if (operationRetryCount > MAX_OPERATION_RETRIES) {
            NayaxLog.e(TAG, "操作超时，已达最大重试次数，状态: " + currentState);
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
                int cents = Math.round(paymentAmount / NayaxCommand.RESOLUTION);
                sendCommand(NayaxCommand.requestDeduction(paymentChannel, cents));
                scheduleOperationTimeout();
                break;
            case REPORTING_RESULT:
                sendCommand(lastVendingSuccess ? NayaxCommand.vendingSuccess() : NayaxCommand.vendingFail());
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
                break;

            case MSG_POLL_STATUS:
                if (paying) {
                    // 支付中：发送状态查询
                    sendCommand(NayaxCommand.queryStatus());
                    schedulePaymentPoll();
                }
                break;

            case MSG_PAYMENT_TIMEOUT:
                NayaxLog.w(TAG, "收款超时");
                handler.removeMessages(MSG_POLL_STATUS);
                paying       = false;
                currentState = State.HEARTBEAT;
                notifyError(ERROR_PAYMENT_TIMEOUT, "收款超时");
                NayaxCallback cb = this.callback;
                if (cb != null) cb.onPaymentCancelled();
                scheduleHeartbeat();
                break;

            case MSG_RECONNECT:
                openSerialPort();
                break;

            case MSG_HEARTBEAT:
                performHeartbeat();
                break;

            case MSG_STREAM_CHECK:
                if (!firstDataReceived && serialUtil != null && !deviceReady) {
                    NayaxLog.w(TAG, "串口打开 1.5s 内未收到任何数据，接收线程可能已死"
                            + "（LSerialUtil HEX输入流已结束）。\n"
                            + "请确认 Nayax 设备已上电并连接至 ttyS" + serialPort);
                }
                break;

            case MSG_INIT_TIMEOUT:
                if (!deviceReady) {
                    NayaxLog.w(TAG, "设备初始化超时，尝试重连");
                    notifyError(ERROR_INIT_TIMEOUT, "设备初始化超时");
                    cancelAllMessages();
                    closeSerialPort();
                    reconnectCount = 0;
                    attemptReconnect();
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
