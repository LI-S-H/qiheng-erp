package com.qiheng.erp.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

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

    @Test
    void shouldSerializeNumericBigDecimalWithSpringHandlerInstantiator() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(JacksonConfig.class)) {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder().applicationContext(context);
            context.getBean(org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer.class)
                    .customize(builder);
            ObjectMapper springObjectMapper = builder.build();

            String json = springObjectMapper.writeValueAsString(new NumericAmountView(new BigDecimal("12.345")));

            assertEquals("{\"amount\":12.345}", json);
        }
    }

    private record AmountView(@JsonSerialize(using = MoneyStringSerializer.class) BigDecimal amount) {
    }

    private record NumericAmountView(@JsonSerialize(using = BigDecimalNumberSerializer.class) BigDecimal amount) {
    }
}
