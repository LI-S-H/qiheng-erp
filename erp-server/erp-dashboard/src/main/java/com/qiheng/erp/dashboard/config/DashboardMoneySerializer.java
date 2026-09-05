package com.qiheng.erp.dashboard.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 工作台金额序列化器：保留金额正负号，最多两位小数并去除尾随 0。
 *
 * <p>毛利额可能为负数，不能复用仅用于销售额、采购额等非负业务金额的序列化规则。</p>
 *
 * @author Li
 * @since 2026-08-28
 */
public class DashboardMoneySerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        if (value == null) {
            generator.writeString("0");
            return;
        }
        generator.writeString(stripTrailingZeros(value.setScale(2, RoundingMode.HALF_UP)));
    }

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