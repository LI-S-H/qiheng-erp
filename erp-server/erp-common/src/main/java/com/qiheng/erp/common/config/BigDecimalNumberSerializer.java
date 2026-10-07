package com.qiheng.erp.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;

/** 将 BigDecimal 按 JSON number 输出，供接口中非金额的数值字段使用。 */
public class BigDecimalNumberSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        generator.writeNumber(value);
    }
}
