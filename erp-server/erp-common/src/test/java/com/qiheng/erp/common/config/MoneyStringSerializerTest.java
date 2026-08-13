package com.qiheng.erp.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyStringSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeLargeAmountAsPlainTwoDecimalString() throws Exception {
        String json = objectMapper.writeValueAsString(new AmountView(new BigDecimal("21474836.48")));

        assertEquals("{\"amount\":\"21474836.48\"}", json);
    }

    @Test
    void shouldRoundToMoneyScaleBeforeSerializing() throws Exception {
        String json = objectMapper.writeValueAsString(new AmountView(new BigDecimal("12.345")));

        assertEquals("{\"amount\":\"12.35\"}", json);
    }

    private record AmountView(@JsonSerialize(using = MoneyStringSerializer.class) BigDecimal amount) {
    }
}
