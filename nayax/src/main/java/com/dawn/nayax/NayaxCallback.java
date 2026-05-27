package com.dawn.nayax;

/**
 * Nayax 刷卡器事件回调接口
 *
 * <p>所有回调均在主线程（UI线程）执行。</p>
 *
 * <p>典型使用流程：</p>
 * <pre>
 * NayaxManager.getInstance().setCallback(new NayaxCallback() {
 *     {@literal @}Override public void onDeviceReady() { ... }
 *     {@literal @}Override public void onPaymentSuccess(float amount) { ... }
 *     ...
 * });
 * NayaxManager.getInstance().connect(3);          // 连接串口 3
 * // 等待 onDeviceReady() 后调用：
 * NayaxManager.getInstance().startPayment(2.5f);  // 发起 2.50 元收款
 * // 等待 onPaymentSuccess() 后调用：
 * NayaxManager.getInstance().reportVendingResult(true);
 * </pre>
 */
public interface NayaxCallback {

    /**
     * 设备已就绪
     * <p>复位成功并收到第一条状态响应后触发。此后可调用 {@link NayaxManager#startPayment}。</p>
     */
    void onDeviceReady();

    /**
     * 扣款请求已被设备接受，等待交易完成
     * <p>仅在设备确认接受扣款指令（FC=10 响应正常）后触发。</p>
     */
    void onPaymentStarted();

    /**
     * 刷卡成功（MDB L1/L2 模式下用户已刷卡，等待扣款）
     * <p>MDB L3 模式通常不会触发此回调（直接走扣款流程）。</p>
     */
    default void onCardSwiped() {}

    /**
     * 扣款成功，交易完成
     *
     * @param amount 本次收款金额（元，与 {@link NayaxManager#startPayment} 传入值一致）
     */
    void onPaymentSuccess(float amount);

    /**
     * 收款已取消（主动取消或超时）
     */
    void onPaymentCancelled();

    /**
     * 出货结果已被设备确认
     *
     * @param success true 表示已上报出货成功，false 表示已上报出货失败
     */
    void onVendingAck(boolean success);

    /**
     * 错误事件
     *
     * @param errorCode 错误码，见 {@link NayaxManager} 中的 {@code ERROR_*} 常量
     * @param message   错误描述
     */
    void onError(int errorCode, String message);

    /**
     * 串口连接状态变化
     *
     * @param connected true 表示已连接，false 表示已断开
     */
    void onConnectionChanged(boolean connected);
}
