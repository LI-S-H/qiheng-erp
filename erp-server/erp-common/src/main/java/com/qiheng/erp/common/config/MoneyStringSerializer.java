package com.qiheng.erp.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 将交易金额以十进制字符串输出，避免浏览器 Number 在大额场景丢失分值精度。 */
public class MoneyStringSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        // 订单金额、单价按分落库；此处固定输出两位，避免前端 Number 丢失分值精度。
        generator.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }
}
