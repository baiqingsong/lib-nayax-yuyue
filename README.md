# lib-nayax

Nayax 刷卡器 Android 库，基于 MDB-232 转换盒的 Modbus RTU 串口协议，实现刷卡扣款全流程。

## 特性

- 最简 API：连接、初始化、收款、取消，4 个核心操作
- 收款成功后**自动上报出货成功**，无需外部干预
- `startPayment` **自动检测设备状态**：设备未初始化时自动初始化；有挂起状态时先发出货失败再重试
- 指令最小间隔 500ms，防止通讯卡顿
- 心跳保活（5s 轮询），防止设备超时禁用刷卡器
- 自动重连（断开后最多重试 5 次，指数退避）
- 初始化、操作响应超时均自动重试（最多 3 次）

## 引入方式

### JitPack（推荐）

项目根 `build.gradle`：

```groovy
allprojects {
    repositories {
        maven { url "https://jitpack.io" }
    }
}
```

app 模块 `build.gradle`：

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-nayax-yuyue:latest.release'
}
```

### 本地模块

`settings.gradle`：

```groovy
include ':nayax'
project(':nayax').projectDir = new File('path/to/nayax')
```

app 模块 `build.gradle`：

```groovy
dependencies {
    implementation project(path: ':nayax')
}
```

## 快速开始

```java
NayaxManager mgr = NayaxManager.getInstance();

// 1. 设置回调
mgr.setCallback(new NayaxCallback() {
    @Override
    public void onConnectionChanged(boolean connected) {
        Log.i("Nayax", "串口连接状态: " + connected);
    }

    @Override
    public void onDeviceReady() {
        Log.i("Nayax", "设备就绪，可以发起收款");
    }

    @Override
    public void onPaymentStarted() {
        Log.i("Nayax", "等待交易完成...");
    }

    @Override
    public void onPaymentSuccess(float amount) {
        Log.i("Nayax", "收款成功: " + amount + " 元");
        // 出货结果由库内部自动上报，此处无需调用任何方法
    }

    @Override
    public void onPaymentCancelled() {
        Log.i("Nayax", "收款已取消");
    }

    @Override
    public void onError(int errorCode, String message) {
        Log.e("Nayax", "错误[" + errorCode + "]: " + message);
    }
});

// 2. 连接串口（在 onCreate 或适当时机）
mgr.connect(7);          // 串口号根据硬件配置

// 3. 初始化设备（收到 onConnectionChanged(true) 后调用）
mgr.init();

// 4. 发起收款（收到 onDeviceReady() 后调用）
mgr.startPayment(2.50f);          // 2.50 元，默认货道 1
// mgr.startPayment(2.50f, 2);    // 指定货道 2

// 5. 取消收款（如需要）
mgr.cancelPayment();

// 6. 释放资源（在 onDestroy 中）
mgr.release();
```

> **注意**：`startPayment` 内部会自动处理以下情况：
> - 若设备尚未初始化，自动触发初始化，初始化完成后自动继续收款
> - 若设备有挂起状态（上次交易未正常结束），自动发出货失败清除后再收款
> - 无需手动判断 `isReady()` 后再调用

## 使用流程

```
connect(port)
    │
    ▼
onConnectionChanged(true)
    │
    ▼
init()
    │
    ▼
onDeviceReady()
    │
    ▼
startPayment(amount)
    │
    ├─ [设备未初始化] → 自动 init() → onDeviceReady() → 继续
    ├─ [设备有挂起状态] → 发出货失败 → 等待状态清除 → 继续
    │
    ▼
onPaymentStarted()          // 扣款指令已被设备接受
    │
    ▼（自动轮询 yy=2）
    │
    ▼
onPaymentSuccess(amount)    // 扣款成功
    │
    ▼（库内部自动上报出货成功）
    │
    ▼
恢复心跳轮询

取消：cancelPayment() → onPaymentCancelled()
```

## API 文档

### NayaxManager

单例，通过 `NayaxManager.getInstance()` 获取。

#### 公开方法

| 方法 | 说明 |
|------|------|
| `setCallback(NayaxCallback)` | 设置事件回调 |
| `connect(int port)` | 打开串口，自动发送复位并初始化 |
| `init()` | 手动发起初始化（可重复调用，如初始化失败重试） |
| `disconnect()` | 断开连接，清理状态 |
| `release()` | 释放资源并销毁单例 |
| `startPayment(float amount)` | 发起收款（默认货道 1） |
| `startPayment(float amount, int channel)` | 发起收款（指定货道） |
| `cancelPayment()` | 取消当前收款 |
| `isConnected()` | 串口是否已连接 |
| `isReady()` | 设备是否已就绪 |
| `isPaying()` | 是否正在收款 |
| `setLogEnabled(boolean)` | 开启/关闭调试日志（默认关闭） |

#### 错误码常量

| 常量 | 值 | 说明 |
|------|---|------|
| `ERROR_PORT_OPEN` | 1 | 串口打开失败 |
| `ERROR_SEND` | 2 | 发送失败 |
| `ERROR_RECEIVE` | 3 | 接收异常 |
| `ERROR_PAYMENT_TIMEOUT` | 4 | 收款超时（120 秒） |
| `ERROR_INVALID_RESPONSE` | 5 | 响应数据无效/扣款被拒绝 |
| `ERROR_RECONNECT_EXHAUSTED` | 6 | 重连次数耗尽（最多 5 次） |
| `ERROR_NOT_READY` | 7 | 设备未就绪 |
| `ERROR_INIT_TIMEOUT` | 8 | 初始化超时（20 秒） |
| `ERROR_OPERATION_TIMEOUT` | 9 | 操作响应超时（最多重试 3 次） |
| `ERROR_DEVICE_FAULT` | 10 | 刷卡器硬件异常 |

---

### NayaxCallback

所有回调均在**主线程**执行。

| 回调方法 | 触发时机 |
|---------|---------|
| `onConnectionChanged(boolean)` | 串口连接状态变化 |
| `onDeviceReady()` | 复位+分辨率查询完成，设备可收款 |
| `onPaymentStarted()` | 扣款请求已被设备接受（FC=10 ACK） |
| `onPaymentSuccess(float amount)` | 扣款成功，出货结果已由库自动上报 |
| `onPaymentCancelled()` | 收款取消（主动取消或超时） |
| `onError(int code, String message)` | 发生错误 |

---

### NayaxCommand

Modbus RTU 指令构造器（工具类，通常不需直接调用）。

| 方法 | 说明 | 完整帧 |
|------|------|--------|
| `reset()` | 复位转换盒（初始化用） | `01 06 00 08 00 01 C9 C8` |
| `queryStatus()` | 查询设备及刷卡器状态 | `01 03 00 00 00 01 84 0A` |
| `queryResolution()` | 查询金额分辨率 | `01 03 00 F7 00 01 35 F8` |
| `requestDeduction(channel, units)` | 刷卡扣款请求（FC=10） | `01 10 00 03 00 02 ...` |
| `vendingSuccess()` | 上报出货成功（收款成功后自动调用） | `01 06 00 05 00 01 58 0B` |
| `vendingFail()` | 上报出货失败（取消/超时时调用） | `01 06 00 05 00 00 99 CB` |
| `validateCRC(frame)` | 验证帧 CRC16-Modbus | — |

---

### 日志控制

调试日志默认**关闭**：

```java
NayaxManager.setLogEnabled(true);   // 开启
NayaxManager.setLogEnabled(false);  // 关闭（默认）
```

> Logcat Tag 统一为 `NayaxManager`。

## 协议说明

| 参数 | 值 |
|------|---|
| 通信接口 | RS232（通过 MDB-232 转换盒） |
| 协议 | 自定义 Modbus RTU |
| 设备地址 | `0x01` |
| 波特率 | 9600，8N1 |
| CRC | CRC16-Modbus（低字节在前） |
| 默认分辨率 | `0.01`（即 `100` = 1.00 元） |
| 心跳间隔 | 5s（防止设备超时禁用刷卡器，超时阈值 10s） |
| 指令最小间隔 | 500ms |
| 收款超时 | 120s |


## 协议说明

通过 MDB-232 转换盒与 Nayax 刷卡器通信：

- 通信接口：RS232
- 协议：自定义 Modbus-RTU
- 设备地址：`0x01`
- 波特率：9600，无校验，8数据位，1停止位
- CRC：CRC16-Modbus（低字节在前）
- 默认金额分辨率：`0.01`（即 100 = 1.00 元）
- **注意：通讯间隔 >10s 转换盒将自动禁用刷卡器，库内部每 5s 自动心跳保活**

### MDB 等级

| 等级 | 说明 |
|------|------|
| **L3（默认）** | 直接下发扣款金额，无需等待用户先刷卡 |
| L1 / L2 | 须等待用户刷卡后再下发扣款指令 |

## 引入方式

### JitPack（推荐）

项目根 `build.gradle`：

```groovy
allprojects {
    repositories {
        maven { url "https://jitpack.io" }
    }
}
```

app 模块 `build.gradle`：

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-nayax:latest'
}
```

### 本地模块

`settings.gradle`：

```groovy
include ':nayax'
```

app 模块 `build.gradle`：

```groovy
dependencies {
    implementation project(path: ':nayax')
}
```

## 使用流程

```
connect(port)
    │
    └─► onDeviceReady()          // 复位成功，可发起收款
            │
        startPayment(amount)
            │
            └─► onPaymentStarted()   // 扣款指令已被接受
                    │
                (自动轮询 yy=2)
                    │
                onPaymentSuccess(amount)   // 扣款成功
                    │
            reportVendingResult(true/false)
                    │
                onVendingAck(success)      // 出货结果已确认
```

取消：`cancelPayment()` → `onPaymentCancelled()`

## API 文档

### NayaxManager

单例，通过 `NayaxManager.getInstance()` 获取。

#### 公开方法

| 方法 | 说明 |
|------|------|
| `setCallback(NayaxCallback)` | 设置事件回调 |
| `setMdbLevel(int)` | 设置 MDB 等级（1/2/3，默认 3） |
| `connect(int port)` | 连接串口并发送复位指令 |
| `disconnect()` | 断开连接，清理状态 |
| `release()` | 释放资源并销毁单例 |
| `startPayment(float amount)` | 发起收款（默认货道 1） |
| `startPayment(float amount, int channel)` | 发起收款（指定货道） |
| `cancelPayment()` | 取消当前收款 |
| `reportVendingResult(boolean success)` | 上报出货结果（成功/失败） |
| `isConnected()` | 串口是否已连接 |
| `isReady()` | 设备是否已就绪 |
| `isPaying()` | 是否正在收款 |
| `getCurrentPort()` | 当前串口号 |
| `getMdbLevel()` | 当前 MDB 等级 |

#### 错误码常量

| 常量 | 值 | 说明 |
|------|---|------|
| `ERROR_PORT_OPEN` | 1 | 串口打开失败 |
| `ERROR_SEND` | 2 | 发送失败 |
| `ERROR_RECEIVE` | 3 | 接收异常 |
| `ERROR_PAYMENT_TIMEOUT` | 4 | 收款超时（120秒） |
| `ERROR_INVALID_RESPONSE` | 5 | 响应数据无效/扣款被拒绝 |
| `ERROR_RECONNECT_EXHAUSTED` | 6 | 重连次数耗尽（最多5次） |
| `ERROR_NOT_READY` | 7 | 设备未就绪 |
| `ERROR_INIT_TIMEOUT` | 8 | 初始化超时（20秒） |
| `ERROR_OPERATION_TIMEOUT` | 9 | 操作响应超时（最多重试3次） |
| `ERROR_DEVICE_FAULT` | 10 | 刷卡器硬件异常 |

---

### NayaxCallback

所有回调均在**主线程**执行。

| 回调方法 | 触发时机 |
|---------|---------|
| `onDeviceReady()` | 复位成功，设备就绪 |
| `onPaymentStarted()` | 扣款请求已被设备接受（FC=10 ACK） |
| `onCardSwiped()` | 用户刷卡（L1/L2 模式，default 方法） |
| `onPaymentSuccess(float amount)` | 扣款成功（状态 yy=2） |
| `onPaymentCancelled()` | 收款取消（主动取消或超时） |
| `onVendingAck(boolean success)` | 出货结果已被设备确认 |
| `onError(int code, String message)` | 发生错误 |
| `onConnectionChanged(boolean connected)` | 串口连接状态变化 |

---

### NayaxCommand

Modbus RTU 指令构造器（工具类，通常不需直接调用）。

| 方法 | 说明 | 发送帧 |
|------|------|--------|
| `reset()` | 复位转换盒 | `01 06 00 08 00 01 C9 C8` |
| `queryStatus()` | 查询设备及刷卡器状态 | `01 03 00 00 00 01 84 0A` |
| `requestDeduction(channel, cents)` | 刷卡扣款请求（FC=10） | `01 10 00 03 00 02 04 ...` |
| `vendingSuccess()` | 上报出货成功 | `01 06 00 05 00 01 58 0B` |
| `vendingFail()` | 上报出货失败 | `01 06 00 05 00 00 99 CB` |
| `enableCardReader()` | 使能刷卡器 | `01 06 00 07 00 00 ...` |
| `disableCardReader()` | 禁止刷卡器 | `01 06 00 07 00 04 ...` |
| `validateCRC(frame)` | 验证帧 CRC16-Modbus | — |

---

### NayaxLog / 日志控制

调试日志默认**关闭**，通过以下方式开启：

```java
// 方式一：通过 NayaxManager（推荐）
NayaxManager.setLogEnabled(true);    // 开启
NayaxManager.setLogEnabled(false);   // 关闭（默认）

// 方式二：直接使用 NayaxLog
NayaxLog.setEnabled(true);
```

> 日志 Tag 统一为 `NayaxManager`，可在 Logcat 中过滤。

#### 各级别日志说明

| 级别 | 内容 |
|------|------|
| `Log.i` | 连接/断开串口、复位指令、收款发起、出货上报、重连、状态跳转等关键流程节点 |
| `Log.d` | 原始收到字节、帧解析过程、心跳状态字节（xx/yy）、发送的完整 HEX 指令 |
| `Log.w` | 重复请求（如重复 startPayment）、操作超时重试、串口运行中断开 |
| `Log.e` | 串口打开/接收/发送异常、操作超时耗尽重试次数、设备硬件异常 |

## 完整使用示例

```java
// 1. 设置回调
NayaxManager.getInstance().setCallback(new NayaxCallback() {
    @Override
    public void onDeviceReady() {
        // 设备就绪，发起收款
        NayaxManager.getInstance().startPayment(2.50f);   // 2.50 元，货道1
        // 或指定货道：
        // NayaxManager.getInstance().startPayment(2.50f, 2);
    }

    @Override
    public void onPaymentStarted() {
        // 显示"等待交易完成..."
    }

    @Override
    public void onPaymentSuccess(float amount) {
        // 扣款成功，出货，然后上报结果
        boolean vendingOk = doVending();
        NayaxManager.getInstance().reportVendingResult(vendingOk);
    }

    @Override
    public void onPaymentCancelled() {
        // 收款已取消
    }

    @Override
    public void onVendingAck(boolean success) {
        // 流程结束
    }

    @Override
    public void onError(int errorCode, String message) {
        Log.e("Nayax", "错误[" + errorCode + "]: " + message);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        Log.i("Nayax", "连接状态: " + connected);
    }
});

// 2. 连接（在 onCreate 或适当时机）
NayaxManager.getInstance().connect(3);   // 串口号根据硬件配置

// 3. 释放资源（在 onDestroy 中）
NayaxManager.getInstance().release();
```

### L1/L2 模式示例

```java
NayaxManager.getInstance().setMdbLevel(1);   // 设置 L1 模式
NayaxManager.getInstance().setCallback(new NayaxCallback() {
    @Override
    public void onDeviceReady() {
        // L1/L2 模式：先启动收款等待，用户刷卡后自动发扣款请求
        NayaxManager.getInstance().startPayment(5.00f);
    }

    @Override
    public void onCardSwiped() {
        // 用户已刷卡，库内部自动发扣款请求
    }

    // ... 其余回调同上
});
```
