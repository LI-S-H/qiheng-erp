package com.qiheng.erp.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 工作台统计时间源配置。
 *
 * <p>工作台的自然日、自然月边界统一使用业务时区，避免部署机器默认时区不同导致统计窗口偏移。</p>
 *
 * @author Li
 * @since 2026-09-04
 */
@Configuration
public class DashboardClockConfig {

    /** 工作台业务时区，与接口时间序列化和开发库连接时区保持一致。 */
    private static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock dashboardClock() {
        return Clock.system(BUSINESS_ZONE_ID);
    }
}
