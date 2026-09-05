package com.qiheng.erp.dashboard.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 工作台非负金额序列化器：负值钳到 0，最多 2 位小数，去尾随 0。
 *
 * <p>对应 OpenAPI {@code DashboardTrendPoint.salesAmount / purchaseAmount}
 * 和 {@code DashboardTopProduct.salesAmount} 的字符串约束。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public class DashboardNonNegativeMoneySerializer extends JsonSerializer<BigDecimal> {

    /**
     * 把 BigDecimal 序列化为最多 2 位小数的字符串，负值按 0 输出
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        if (value == null) {
            generator.writeString("0");
            return;
        }
        BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) {
            normalized = BigDecimal.ZERO.setScale(2);
        }
        generator.writeString(stripTrailingZeros(normalized));
    }

    /** 去掉 BigDecimal 字符串形式的尾随 0，保留至多 2 位小数 */
    private static String stripTrailingZeros(BigDecimal value) {
        String plain = value.toPlainString();
        if (!plain.contains(".")) {
            return plain;
        }
        int end = plain.length();
        while (end > 0 && plain.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && plain.charAt(end - 1) == '.') {
            end--;
        }
        return plain.substring(0, end);
    }
}