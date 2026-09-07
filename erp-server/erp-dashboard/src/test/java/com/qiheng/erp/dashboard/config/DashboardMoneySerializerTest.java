package com.qiheng.erp.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.dashboard.domain.trend.vo.DashboardTrendPointVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardMoneySerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeNegativeGrossMarginWithoutClamping() throws Exception {
        DashboardTrendPointVO point = new DashboardTrendPointVO();
        point.setGrossMarginAmount(new BigDecimal("-60.125"));

        String json = objectMapper.writeValueAsString(point);

        assertThat(json).contains("\"grossMarginAmount\":\"-60.13\"");
    }
}