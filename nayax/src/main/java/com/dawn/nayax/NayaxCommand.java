package com.dawn.nayax;

/**
 * Nayax 刷卡器 MDB-232 Modbus RTU 指令构造器
 *
 * <p>协议规范：
 * <ul>
 *   <li>通信接口：RS232</li>
 *   <li>协议：自定义 Modbus-RTU</li>
 *   <li>设备地址：0x01</li>
 *   <li>波特率：9600，无校验，8数据位，1停止位</li>
 *   <li>CRC：CRC16-Modbus（低字节在前）</li>
 *   <li>默认金额分辨率：0.01（即 100 = 1.00 元）</li>
 * </ul>
 *
 * <p>注意：通讯超时 &gt;10s，转换盒会禁止外设功能，需定期轮询。
 */
public final class NayaxCommand {

    /** 默认金额分辨率：0.01 */
    public static final float RESOLUTION = 0.01f;

    private NayaxCommand() {}

    // ==================== 刷卡器相关指令 ====================

    /**
     * 复位 MDB 转换盒（使能所有外设）
     * <p>FC=06, 地址 0x0008, 数据 0x0001</p>
     * 发送: {@code 01 06 00 08 00 01 C9 C8}
     * 响应: echo 相同帧
     */
    public static String reset() {
        return appendCRC("010600080001");
    }

    /**
     * 查询设备及刷卡器状态
     * <p>FC=03, 地址 0x0000, 寄存器数 1</p>
     * 发送: {@code 01 03 00 00 00 01 84 0A}
     * 响应: {@code 01 03 02 xx yy CRC_L CRC_H}
     * <ul>
     *   <li>xx bit2：刷卡器硬件状态（0=正常, 1=异常）</li>
     *   <li>xx bit6：刷卡器使能状态（0=使能, 1=禁止）</li>
     *   <li>yy：刷卡器当前支付状态（见 {@link CardState}）</li>
     * </ul>
     */
    public static String queryStatus() {
        return appendCRC("010300000001");
    }

    /**
     * 刷卡扣款请求（协议 3.2.5）
     * <p>FC=10，地址 0x0003（货道）+ 0x0004（价格），无 byte_count 字段（自定义协议）。</p>
     *
     * <p>发送格式：
     * <pre>
     * 01 10 00 03 00 02 [chan_H chan_L] [price_H price_L] CRC_L CRC_H
     * </pre>
     * 响应格式（正常）：{@code 01 10 00 00 00 02 41 C8}<br>
     * 响应格式（异常）：{@code 01 90 00 00 00 02 40 16}
     * </p>
     *
     * <p>适用于 MDB L3 模式（直接扣款）。
     * MDB L1/L2 模式须先等待 {@link CardState#SWIPED} 再调用本指令。</p>
     *
     * @param channel     货道编号（从 1 开始）
     * @param amountUnits 扣款金额（按分辨率换算后的整数单位，如分辨率 0.01 时单位为分）
     */
    public static String requestDeduction(int channel, int amountUnits) {
        String chanHex  = String.format("%04X", channel & 0xFFFF);
        String priceHex = String.format("%04X", amountUnits & 0xFFFF);
        // 注意：该协议不包含标准 Modbus RTU FC=10 的 byte_count 字段
        return appendCRC("011000030002" + chanHex + priceHex);
    }

    /**
     * 查询分辨率（协议 3.2.19）
     * <p>FC=03, 地址 0x00F7, 寄存器数 1</p>
     * 发送: {@code 01 03 00 F7 00 01 35 F8}<br>
     * 响应: {@code 01 03 02 00 xx CRC_L CRC_H}
     * <ul>
     *   <li>xx=0: 分辨率 0.01</li>
     *   <li>xx=1: 分辨率 1</li>
     *   <li>xx=2: 分辨率 10</li>
     *   <li>xx=3: 分辨率 100</li>
     * </ul>
     */
    public static String queryResolution() {
        return appendCRC("010300F70001");
    }

    /**
     * 从分辨率响应帧中解析实际分辨率值
     * <p>帧格式与状态响应相同: {@code 01 03 02 00 xx CRC}</p>
     *
     * @param frame 已通过 CRC 校验的完整帧
     * @return 分辨率浮点数（如 0.01f、1.0f、10.0f、100.0f）；解析失败返回默认值 {@value #RESOLUTION}
     */
    public static float parseResolutionValue(String frame) {
        if (!isStatusResponse(frame)) return RESOLUTION;
        try {
            int code = Integer.parseInt(frame.substring(8, 10), 16);
            switch (code) {
                case 0: return 0.01f;
                case 1: return 1.0f;
                case 2: return 10.0f;
                case 3: return 100.0f;
                default: return RESOLUTION;
            }
        } catch (NumberFormatException e) {
            return RESOLUTION;
        }
    }

    /**
     * 上报出货成功
     * <p>FC=06, 地址 0x0005, 数据 0x0001</p>
     * 发送: {@code 01 06 00 05 00 01 58 0B}
     */
    public static String vendingSuccess() {
        return appendCRC("010600050001");
    }

    /**
     * 上报出货失败
     * <p>FC=06, 地址 0x0005, 数据 0x0000</p>
     * 发送: {@code 01 06 00 05 00 00 99 CB}
     */
    public static String vendingFail() {
        return appendCRC("010600050000");
    }

    /**
     * 使能刷卡器（清除禁止标志）
     * <p>FC=06, 地址 0x0007, 所有位为 0（全部使能）</p>
     */
    public static String enableCardReader() {
        return appendCRC("010600070000");
    }

    /**
     * 禁止刷卡器（bit2 置 1）
     * <p>FC=06, 地址 0x0007, 数据 0x0004（bit2=1）</p>
     */
    public static String disableCardReader() {
        return appendCRC("010600070004");
    }

    /**
     * 查询 MDB 等级（L1/L2/L3）
     * <p>FC=03, 地址 0x00F5, 寄存器数 1</p>
     * 响应: {@code 01 03 02 Ln Lm CRC}（Ln=盒子等级, Lm=刷卡器实际等级）
     */
    public static String queryMdbLevel() {
        return appendCRC("010300F50001");
    }

    // ==================== 响应解析辅助 ====================

    /**
     * 从状态响应帧中提取设备状态字节 xx
     * <p>仅适用于 FC=03 状态响应: {@code 01 03 02 xx yy CRC}（7字节=14字符）</p>
     *
     * @param frame 完整的 HEX 帧字符串（已通过 CRC 校验）
     * @return 设备状态字节，-1 表示格式不合法
     */
    public static int parseDeviceStatus(String frame) {
        if (!isStatusResponse(frame)) return -1;
        try {
            return Integer.parseInt(frame.substring(6, 8), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 从状态响应帧中提取刷卡器状态字节 yy
     *
     * @param frame 完整的 HEX 帧字符串（已通过 CRC 校验）
     * @return 刷卡器状态字节，-1 表示格式不合法
     */
    public static int parseCardState(String frame) {
        if (!isStatusResponse(frame)) return -1;
        try {
            return Integer.parseInt(frame.substring(8, 10), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 判断帧是否为 FC=03 状态响应（14 字符）
     */
    public static boolean isStatusResponse(String frame) {
        return frame != null
                && frame.length() == 14
                && frame.toUpperCase().startsWith("010302");
    }

    /**
     * 判断帧是否为 FC=10 扣款成功响应
     * <p>设备固件返回的起始地址为 0x0000（非标准）：{@code 01 10 00 00 00 02 CRC}</p>
     */
    public static boolean isDeductionSuccessResponse(String frame) {
        return frame != null
                && frame.length() == 16
                && frame.toUpperCase().startsWith("01100000")
                && validateCRC(frame);
    }

    /**
     * 判断帧是否为 FC=10 扣款异常响应（功能码 0x90 = 0x10 | 0x80）
     */
    public static boolean isDeductionErrorResponse(String frame) {
        return frame != null
                && frame.toUpperCase().startsWith("0190");
    }

    /**
     * 判断帧是否为 FC=06 写单寄存器 echo 响应（16 字符）
     */
    public static boolean isWriteSingleAck(String frame) {
        return frame != null
                && frame.length() == 16
                && frame.toUpperCase().startsWith("0106")
                && validateCRC(frame);
    }

    /**
     * 验证 HEX 帧的 CRC16-Modbus 校验
     *
     * @param frame 完整帧（含尾部 4 位 CRC）
     * @return true 表示校验通过
     */
    public static boolean validateCRC(String frame) {
        if (frame == null || frame.length() < 6 || frame.length() % 2 != 0) {
            return false;
        }
        String dataPart = frame.substring(0, frame.length() - 4);
        String crcPart  = frame.substring(frame.length() - 4).toUpperCase();
        byte[] bytes = hexToBytes(dataPart);
        if (bytes == null) return false;
        return crcPart.equals(computeCRC(bytes).toUpperCase());
    }

    // ==================== CRC16-Modbus ====================

    /**
     * 为十六进制数据字符串追加 CRC16-Modbus 校验码（低字节在前）
     *
     * @param hexData 不含 CRC 的十六进制字符串（偶数长度）
     * @return 包含 CRC 的完整帧；数据无效时返回 null
     */
    static String appendCRC(String hexData) {
        if (hexData == null) return null;
        hexData = hexData.replace(" ", "").toUpperCase();
        if (hexData.isEmpty() || hexData.length() % 2 != 0) return null;
        byte[] bytes = hexToBytes(hexData);
        if (bytes == null) return null;
        return hexData + computeCRC(bytes);
    }

    /**
     * 计算 CRC16-Modbus（低字节在前，高字节在后）
     *
     * @param bytes 字节数组
     * @return 4 位十六进制字符串，低字节在前
     */
    static String computeCRC(byte[] bytes) {
        int crc = 0xFFFF;
        for (byte b : bytes) {
            crc ^= (b & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        // 低字节在前，高字节在后
        String result = String.format("%04X", crc & 0xFFFF);
        return result.substring(2, 4) + result.substring(0, 2);
    }

    /**
     * 十六进制字符串转字节数组
     */
    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        int len = hex.length() / 2;
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            try {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    // ==================== 刷卡器状态常量 ====================

    /**
     * 刷卡器当前支付状态（状态响应中的 yy 字节）
     */
    public static final class CardState {
        /** 空闲/正常 */
        public static final int IDLE    = 0;
        /** 已刷卡（MDB L1/L2 模式等待扣款指令） */
        public static final int SWIPED  = 1;
        /** 扣款成功 */
        public static final int DEDUCTED = 2;
        /** 硬币器退币杆被按下 */
        public static final int REFUND_LEVER = 3;

        private CardState() {}
    }
}
