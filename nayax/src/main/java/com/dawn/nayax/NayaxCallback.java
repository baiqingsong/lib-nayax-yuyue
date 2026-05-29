package com.dawn.nayax;

/**
 * Nayax 刷卡器事件回调接口
 *
 * <p>所有回调均在主线程（UI线程）执行。</p>
 *
 * <p>典型使用流程：</p>
 * <pre>
 * NayaxManager mgr = NayaxManager.getInstance();
 * mgr.setCallback(callback);
 * mgr.connect(7);           // 1. 打开串口 → onConnectionChanged(true)
 * mgr.init();               // 2. 初始化   → onDeviceReady()
 * mgr.startPayment(2.50f);  // 3. 发起收款 → onPaymentStarted() → onPaymentSuccess()
 * // 出货结果由库内部自动上报，无需额外调用
 * </pre>
 */
public interface NayaxCallback {

    /**
     * 串口连接状态变化
     *
     * @param connected true 表示串口已打开，false 表示已断开
     */
    void onConnectionChanged(boolean connected);

    /**
     * 设备初始化成功，可以发起收款
     * <p>复位 + 分辨率查询完成后触发。</p>
     */
    void onDeviceReady();

    /**
     * 扣款请求已被设备接受，等待交易完成
     */
    void onPaymentStarted();

    /**
     * 收款成功，交易完成
     * <p>库内部会自动向设备上报出货成功，无需外部调用。</p>
     *
     * @param amount 本次收款金额（元，与 {@link NayaxManager#startPayment} 传入值一致）
     */
    void onPaymentSuccess(float amount);

    /**
     * 收款已取消（主动取消或超时）
     */
    void onPaymentCancelled();

    /**
     * 错误事件
     *
     * @param errorCode 错误码，见 {@link NayaxManager} 中的 {@code ERROR_*} 常量
     * @param message   错误描述
     */
    void onError(int errorCode, String message);
}
