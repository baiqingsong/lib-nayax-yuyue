package com.dawn.libcardnayax;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.dawn.nayax.NayaxCallback;
import com.dawn.nayax.NayaxLog;
import com.dawn.nayax.NayaxManager;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 调试时开启日志
        NayaxLog.setEnabled(true);

        NayaxManager.getInstance().setCallback(new NayaxCallback() {
            @Override
            public void onDeviceReady() {
                Log.i(TAG, "设备就绪，可以发起收款");
            }

            @Override
            public void onPaymentStarted() {
                Log.i(TAG, "扣款请求已被设备接受，等待交易完成");
            }

            @Override
            public void onCardSwiped() {
                Log.i(TAG, "检测到刷卡（L1/L2 模式）");
            }

            @Override
            public void onPaymentSuccess(float amount) {
                Log.i(TAG, "收款成功: " + amount + " 元");
                // 收款成功后上报出货结果
                NayaxManager.getInstance().reportVendingResult(true);
            }

            @Override
            public void onPaymentCancelled() {
                Log.i(TAG, "收款已取消");
            }

            @Override
            public void onVendingAck(boolean success) {
                Log.i(TAG, "出货结果已确认: " + (success ? "成功" : "失败"));
            }

            @Override
            public void onError(int errorCode, String message) {
                Log.e(TAG, "错误[" + errorCode + "]: " + message);
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                Log.i(TAG, "连接状态: " + connected);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NayaxManager.getInstance().release();
    }

    /** 连接串口（串口号根据实际硬件配置） */
    public void startPort(View view) {
        NayaxManager.getInstance().connect(7);
    }

    /** 断开串口 */
    public void stopPort(View view) {
        NayaxManager.getInstance().disconnect();
    }

    /** 发起随机金额收款（货道 1） */
    public void startMoney(View view) {
        Random random = new Random();
        float amount = 0.01f + random.nextFloat() * 9.99f;
        amount = Math.round(amount * 100) / 100.0f;
        Log.i(TAG, "发起收款: " + amount + " 元");
        NayaxManager.getInstance().startPayment(amount);
    }

    /** 发起固定金额收款（2.00 元，货道 2） */
    public void startMoneyChannel2(View view) {
        Log.i(TAG, "发起收款: 2.00 元，货道 2");
        NayaxManager.getInstance().startPayment(2.0f, 2);
    }

    /** 取消收款 */
    public void cancelMoney(View view) {
        NayaxManager.getInstance().cancelPayment();
    }

    /** 手动上报出货成功 */
    public void reportSuccess(View view) {
        NayaxManager.getInstance().reportVendingResult(true);
    }

    /** 手动上报出货失败 */
    public void reportFail(View view) {
        NayaxManager.getInstance().reportVendingResult(false);
    }
}